package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.SupplierIntegrationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class WelstoryCatalogSyncService {
	private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
	// Welstory's production gateway has been verified with 100 rows per request.
	// Continue through dataHeader.nextKey so the full catalog is still
	// synchronized.
	private static final int PAGE_SIZE = 100;
	private final SupplierIntegrationMapper mapper;
	private final WelstoryItemLookupService api;
	private final ObjectMapper objectMapper;
	private final ReceiptPostingService receiptPosting;
	private final SupplierSiteService siteService;

	public WelstoryCatalogSyncService(SupplierIntegrationMapper mapper, WelstoryItemLookupService api,
			ObjectMapper objectMapper, ReceiptPostingService receiptPosting, SupplierSiteService siteService) {
		this.mapper = mapper;
		this.api = api;
		this.objectMapper = objectMapper;
		this.receiptPosting = receiptPosting;
		this.siteService = siteService;
	}

	/** 웰스토리 전체 사업장을 사업장 마스터에만 저장한다. 거래처 연결은 사업장 매핑에서 따로 지정한다. */
	public Map<String, Object> syncSites(Map<String, Object> command) {
		List<JsonNode> rows = fetchPaged("/fdapi/service/payer-rep-soldto", objectMapper.createObjectNode());
		List<Map<String, Object>> sites = new ArrayList<>();
		for (JsonNode row : rows) {
			String soldTo = first(row, "soldTo", "solTo");
			if (soldTo.isBlank())
				continue;
			Map<String, Object> site = new LinkedHashMap<>();
			site.put("external_site_code", soldTo);
			site.put("external_site_name", first(row, "soldToNm", "solToNm"));
			site.put("representative_site_code", first(row, "repSoldTo", "repSoldto", "repSoldtoCode"));
			site.put("representative_site_name", null);
			site.put("center_code", first(row, "logisCd", "centerCode"));
			site.put("center_name", null);
			site.put("price_context_code", first(row, "pricingCode"));
			site.put("provider_attributes", json(row));
			sites.add(site);
		}
		int saved = siteService.saveProviderSites("WELSTORY", sites, text(command.get("user_id")));
		return Map.of("code", 200, "received_count", rows.size(), "saved_count", saved);
	}

	public Map<String, Object> syncCatalog(Map<String, Object> command) {
		String accountId = required(command, "account_id");
		String soldTo = required(command, "sold_to");
		String year = required(command, "period_group_year");
		String period = required(command, "period_group");
		if (!year.matches("\\d{4}"))
			throw new IllegalArgumentException("period_group_year는 4자리여야 합니다.");
		if (!period.matches("\\d{2}"))
			throw new IllegalArgumentException("period_group은 2자리여야 합니다.");
		Long supplierId = ensureSupplier(command);
		Map<String, Object> siteQuery = new LinkedHashMap<>();
		siteQuery.put("account_id", accountId);
		siteQuery.put("supplier_id", supplierId);
		siteQuery.put("active_yn", "Y");
		Map<String, Object> site = mapper.sites(siteQuery).stream()
				.filter(row -> soldTo.equals(text(row.get("external_site_code")))).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("이 거래처에 지정된 웰스토리 사업장이 아닙니다. 사업장 매핑에서 먼저 지정해 주세요: " + soldTo));
		// 상품·판가·동기화 기록은 사업장 마스터 단위(같은 사업장을 쓰는 거래처가 함께 쓴다)
		Long siteId = Long.valueOf(site.get("supplier_site_id").toString());
		Map<String, Object> checkpoint = checkpoint(supplierId, siteId, "FULL_CATALOG");
		mapper.markSyncStarted(checkpoint);
		try {
			ObjectNode body = objectMapper.createObjectNode();
			body.put("soldTo", soldTo).put("periodGroupYear", year).put("periodGroup", period);
			List<JsonNode> rows = fetchPaged("/fdapi/service/payer-allitem-price", body);
			int saved = 0;
			for (JsonNode row : rows) {
				if (upsertCatalogRow(command, supplierId, siteId, year, period, row))
					saved++;
			}
			checkpoint.put("result_message", "수신 " + rows.size() + "건, 저장 " + saved + "건");
			mapper.markSyncSucceeded(checkpoint);
			return Map.of("code", 200, "received_count", rows.size(), "saved_count", saved, "sold_to", soldTo,
					"period_group_year", year, "period_group", period);
		} catch (RuntimeException exception) {
			checkpoint.put("result_message", abbreviate(exception.getMessage(), 500));
			checkpoint.put("cursor_value", "");
			mapper.markSyncFailed(checkpoint);
			throw exception;
		}
	}

	/**
	 * 순기 한 건(1행)만 조회해 웰스토리가 준 순기 시작·종료일을 돌려준다. DB에 저장하지 않는다.
	 * 아직 열리지 않았거나 없는 순기면 null. 순기 번호를 찾을 때 달력 계산 대신 이 값으로 확인한다.
	 */
	public LocalDate[] periodDates(String soldTo, String year, String period) {
		ObjectNode body = objectMapper.createObjectNode();
		body.put("soldTo", soldTo).put("periodGroupYear", year).put("periodGroup", period);
		// fetchPaged와 같은 순서: contYn=Y로 먼저, '조회 데이터 없음'이면 N으로 한 번 더
		for (String contYn : List.of("Y", "N")) {
			ObjectNode request = objectMapper.createObjectNode();
			request.putObject("dataHeader").put("pageRow", 1).put("contYn", contYn).put("nextKey", "");
			request.set("dataBody", body.deepCopy());
			JsonNode responseBody = api.call("/fdapi/service/payer-allitem-price", request).path("dataBody");
			String code = responseBody.path("resCd").asText("");
			if (!code.isBlank() && !"S0000".equals(code)) {
				if (noData(code, responseBody.path("resMsg").asText("")))
					continue;
				throw new IllegalStateException(responseBody.path("resMsg").asText(code));
			}
			JsonNode data = responseBody.path("data");
			JsonNode row = data.isArray() ? data.path(0) : data;
			try {
				return new LocalDate[] { LocalDate.parse(first(row, "periodFromDt"), BASIC_DATE),
						LocalDate.parse(first(row, "periodToDt"), BASIC_DATE) };
			} catch (Exception e) {
				continue;
			}
		}
		return null;
	}

	/**
	 * 변경 판가(payer-chgitem-price): 요청일에 전일 대비 생성(C)·변경(U)·삭제(D)된 품목만 반영한다.
	 * 전체 판가를 이미 받은 순기에만 쓴다. 변경이 없으면 0건.
	 */
	public Map<String, Object> syncChangedPrices(Map<String, Object> command) {
		String accountId = required(command, "account_id");
		String soldTo = required(command, "sold_to");
		String year = required(command, "period_group_year");
		String period = required(command, "period_group");
		String reqDate = required(command, "req_date");
		Long supplierId = ensureSupplier(command);
		Map<String, Object> siteQuery = new LinkedHashMap<>();
		siteQuery.put("account_id", accountId);
		siteQuery.put("supplier_id", supplierId);
		siteQuery.put("active_yn", "Y");
		Map<String, Object> site = mapper.sites(siteQuery).stream()
				.filter(row -> soldTo.equals(text(row.get("external_site_code")))).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("이 거래처에 지정된 웰스토리 사업장이 아닙니다: " + soldTo));
		// 상품·판가·동기화 기록은 사업장 마스터 단위(같은 사업장을 쓰는 거래처가 함께 쓴다)
		Long siteId = Long.valueOf(site.get("supplier_site_id").toString());
		ObjectNode body = objectMapper.createObjectNode();
		body.put("soldTo", soldTo).put("periodGroupYear", year).put("periodGroup", period).put("reqDate", reqDate);
		List<JsonNode> rows;
		try {
			rows = fetchPaged("/fdapi/service/payer-chgitem-price", body);
		} catch (IllegalStateException e) {
			if (noData("", e.getMessage()))
				return Map.of("code", 200, "received_count", 0, "saved_count", 0);
			throw e;
		}
		int saved = 0;
		for (JsonNode row : rows)
			if (upsertCatalogRow(command, supplierId, siteId, year, period, row))
				saved++;
		return Map.of("code", 200, "received_count", rows.size(), "saved_count", saved);
	}

	private boolean noData(String code, String message) {
		String m = message == null ? "" : message;
		return "EE120".equals(code) || "E1103".equals(code) || m.contains("존재하지 않습니다") || m.contains("조회 결과가 없습니다");
	}

	@Transactional
	public Map<String, Object> syncReceipts(Map<String, Object> command) {
		String accountId = required(command, "account_id");
		String soldTo = required(command, "sold_to");
		String receiptDate = required(command, "receipt_date");
		if (!receiptDate.matches("\\d{8}"))
			throw new IllegalArgumentException("receipt_date는 yyyyMMdd 형식이어야 합니다.");

		ObjectNode request = objectMapper.createObjectNode();
		request.putObject("dataHeader").put("soldTo", soldTo).put("reqDeliveryDate", receiptDate);
		request.putObject("dataBody");
		JsonNode response = api.call("/fdapi/service/payer-receive-detail", request);
		JsonNode responseBody = response.path("dataBody");
		String resultCode = responseBody.path("resCd").asText("");
		if ("EE120".equals(resultCode)) {
			return Map.of("code", 200, "received_count", 0, "posted_count", 0, "unmatched_count", 0, "provider_code",
					resultCode, "message", "조회된 입고 내역이 없습니다.");
		}
		if (!"S0000".equals(resultCode)) {
			throw new IllegalStateException(responseBody.path("resMsg").asText(resultCode));
		}

		JsonNode data = responseBody.path("data");
		if (!data.isArray())
			return Map.of("code", 200, "received_count", 0, "posted_count", 0, "unmatched_count", 0);
		int posted = 0;
		int unmatched = 0;
		for (JsonNode item : data) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("account_id", accountId);
			row.put("sold_to", soldTo);
			row.put("client_ord", first(item, "clientOrd"));
			row.put("client_ord_item", first(item, "clientOrdItem"));
			row.put("supplier_item_code", first(item, "itemCode"));
			Map<String, Object> orderLine = mapper.orderLineForReceipt(row);
			if (orderLine == null) {
				unmatched++;
				continue;
			}
			// 식재료 없이 상품만 발주한 줄은 재고에 넣을 식재료를 사람이 골라야 하므로 자동 입고하지 않는다(발주서 화면에서 입고).
			if (orderLine.get("ingredient_id") == null || text(orderLine.get("ingredient_id")).isBlank()) {
				unmatched++;
				continue;
			}
			row.putAll(orderLine);

			BigDecimal receivedQty = decimal(first(item, "billQty"));
			if (receivedQty.signum() <= 0)
				continue;
			BigDecimal baseQtyPerOrderUnit = decimal(text(orderLine.get("base_qty_per_order_unit")));
			if (baseQtyPerOrderUnit.signum() <= 0)
				baseQtyPerOrderUnit = BigDecimal.ONE;
			BigDecimal baseReceivedQty = receivedQty.multiply(baseQtyPerOrderUnit);
			BigDecimal unitPrice = decimal(first(item, "unitPrice"));
			BigDecimal supplyAmount = decimal(first(item, "supplyPrice"));
			if (supplyAmount.signum() <= 0)
				supplyAmount = unitPrice.multiply(receivedQty);
			BigDecimal vatAmount = decimal(first(item, "vat"));
			BigDecimal totalAmount = decimal(first(item, "totAmt"));
			if (totalAmount.signum() <= 0)
				totalAmount = supplyAmount.add(vatAmount);
			BigDecimal baseUnitCost = baseReceivedQty.signum() == 0 ? BigDecimal.ZERO
					: supplyAmount.divide(baseReceivedQty, 6, RoundingMode.HALF_UP);

			String billDate = valueOr(first(item, "billDate"), receiptDate);
			String receiptKey = soldTo + "|" + billDate + "|" + row.get("client_ord");
			String lineKey = receiptKey + "|" + row.get("client_ord_item") + "|" + row.get("supplier_item_code");
			String receiptId = UUID.nameUUIDFromBytes(("WELSTORY|" + receiptKey).getBytes(StandardCharsets.UTF_8))
					.toString();
			row.put("goods_receipt_id", receiptId);
			row.put("provider_receipt_key", receiptKey);
			row.put("provider_line_key", lineKey);
			row.put("receipt_date", LocalDate.parse(billDate, BASIC_DATE).toString());
			row.put("ordered_qty", orderLine.get("order_qty"));
			row.put("received_qty", receivedQty);
			row.put("order_unit", first(item, "unit"));
			row.put("base_received_qty", baseReceivedQty);
			row.put("unit_price", unitPrice);
			row.put("supply_amount", supplyAmount);
			row.put("vat_amount", vatAmount);
			row.put("total_amount", totalAmount);
			row.put("receipt_amount", supplyAmount);
			row.put("receipt_base_unit_cost", baseUnitCost);
			row.put("traceability_no", first(item, "impHisNo"));
			row.put("traceability_type", first(item, "foodKind"));
			row.put("lot_no", null);
			row.put("expiration_date", null);
			row.put("reconciliation_status",
					receivedQty.compareTo(decimal(text(orderLine.get("order_qty")))) == 0 ? "MATCHED" : "MISMATCH");
			row.put("provider_attributes", json(item));
			row.put("location_id", "L999");
			row.put("user_id", text(command.get("user_id")));
			if (receiptPosting.post(row))
				posted++;
		}
		return Map.of("code", 200, "received_count", data.size(), "posted_count", posted, "unmatched_count", unmatched);
	}

	private boolean upsertCatalogRow(Map<String, Object> command, Long supplierId, Long siteId, String year,
			String period, JsonNode row) {
		String itemCode = first(row, "itemCode");
		String productName = first(row, "itemName", "itemDesc");
		if (itemCode.isBlank() || productName.isBlank())
			return false;
		String unit = valueOr(first(row, "unit"), "EA");
		// 규격은 standard("대란,국산,30EA,1560G이상/PAC")에서 읽고, 비어 있으면 상품명에서 읽는다.
		// 무게·부피와 개수를 모두 저장해 두고, 거래처 연결 시 식재료 기준단위에 맞는 쪽을 쓴다.
		List<QuantityUnits.Pack> packs = QuantityUnits.packs(unit, first(row, "standard"));
		if (packs.isEmpty())
			packs = QuantityUnits.packs(unit, productName);
		QuantityUnits.Pack pack = packs.isEmpty() ? new QuantityUnits.Pack(BigDecimal.ZERO, QuantityUnits.unit(unit))
				: packs.get(0);
		QuantityUnits.Pack alt = packs.size() > 1 ? packs.get(1) : null;
		BigDecimal baseQty = pack.quantity();
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("account_id", required(command, "account_id"));
		p.put("supplier_id", supplierId);
		p.put("supplier_site_id", siteId);
		// 동기화 상품은 본사 식자재로 분류하지 않는다. 거래처 연결은 거래처 식자재 관리에서 직접 한다.
		p.put("ingredient_id", null);
		p.put("alt_base_qty", alt == null ? null : alt.quantity());
		p.put("alt_base_unit", alt == null ? null : alt.baseUnit());
		p.put("supplier_item_code", itemCode);
		p.put("product_name", productName);
		// API 서버가 아닌 응답의 imgDomain을 사용하여 상품별 사진 주소를 저장한다.
		p.putAll(WelstoryProductImages.from(row));
		// 웰스토리는 classA(대)-classD(세) 분류를 제공한다. 화면의 단일 카테고리는
		// 실무에서 식재료군으로 쓰기 좋은 classB를 우선 사용하고 원본 계층은 provider_attributes에 보존한다.
		p.put("category_name",
				first(row, "classB", "classC", "classA", "classD", "middleCategoryName", "categoryName"));
		p.put("base_unit", pack.baseUnit());
		p.put("order_unit", unit);
		p.put("package_qty", baseQty);
		p.put("package_unit", pack.baseUnit());
		p.put("base_qty", baseQty);
		p.put("minimum_order_qty", positive(first(row, "minQntty"), BigDecimal.ONE));
		p.put("order_increment_qty", positive(first(row, "orderIncrs"), BigDecimal.ONE));
		p.put("tax_type", first(row, "taxCode", "taxType"));
		p.put("storage_type", storageType(first(row, "strgTmprt", "storageTemperature")));
		p.put("lead_time_days", integer(first(row, "leadTime")));
		p.put("active_yn", "Y");
		p.put("user_id", text(command.get("user_id")));
		mapper.upsertSupplierProduct(p);
		Long productId = mapper.supplierProductId(p);
		p.put("supplier_product_id", productId);
		p.put("delivery_type", first(row, "deliveryType"));
		p.put("cutoff_code", first(row, "closeCode"));
		// 가이드 closeCode 표(M01 11시, N01·F01 17시). 해당없음·미정의 코드는 시각 없음(확인 필요).
		LocalTime close = OrderAvailability.providerCutoff("WELSTORY", first(row, "closeCode"));
		p.put("cutoff_time", close == null ? null : close.toString());
		p.put("decimal_order_allowed", "Y".equalsIgnoreCase(first(row, "decYN")) ? "Y" : "N");
		String stop = first(row, "stopType");
		p.put("product_status", stop.isBlank() ? "ACTIVE" : "STOPPED");
		p.put("status_reason", stop);
		p.put("valid_from", date(first(row, "periodFromDt"), null));
		p.put("valid_to", date(first(row, "periodToDt"), null));
		p.put("provider_revision", year + "-" + period);
		p.put("provider_attributes", json(row));
		// 변경 판가의 삭제(D) 품목: 이 사업장 판매조건만 내린다(공용 상품 마스터는 그대로)
		if ("D".equalsIgnoreCase(first(row, "statusCd"))) {
			p.put("active_yn", "N");
			mapper.upsertOffer(p);
			return true;
		}
		mapper.upsertOffer(p);
		p.put("price_context_type", "CYCLE");
		p.put("price_context_value", year + "-" + period);
		p.put("purchase_price", decimal(first(row, "price")));
		p.put("currency", "KRW");
		p.put("effective_from", date(first(row, "periodFromDt"), LocalDateTime.now().toString()));
		p.put("effective_to", endDate(first(row, "periodToDt")));
		p.put("price_provider_attributes", json(row));
		mapper.closeOfferPrice(p);
		mapper.insertOfferPrice(p);
		return true;
	}

	private List<JsonNode> fetchPaged(String path, ObjectNode body) {
		List<JsonNode> rows = new ArrayList<>();
		String contYn = "Y", nextKey = "";
		int pages = 0;
		boolean initialRequest = true;
		boolean retriedWithNewQueryFlag = false;
		do {
			ObjectNode request = objectMapper.createObjectNode();
			request.putObject("dataHeader").put("pageRow", PAGE_SIZE).put("contYn", contYn).put("nextKey", nextKey);
			request.set("dataBody", body.deepCopy());
			JsonNode response = api.call(path, request);
			JsonNode responseBody = response.path("dataBody");
			String code = responseBody.path("resCd").asText("");
			if (!code.isBlank() && !"S0000".equals(code)) {
				String message = responseBody.path("resMsg").asText(code);
				if (initialRequest && !retriedWithNewQueryFlag && nextKey.isBlank()
						&& message.contains("조회 데이터가 존재하지 않습니다")) {
					contYn = "N";
					retriedWithNewQueryFlag = true;
					continue;
				}
				throw new IllegalStateException(message);
			}
			JsonNode data = responseBody.path("data");
			if (data.isArray())
				data.forEach(rows::add);
			JsonNode header = response.path("dataHeader");
			contYn = header.path("contYn").asText("N");
			nextKey = header.path("nextKey").asText("");
			initialRequest = false;
			if (++pages > 1000)
				throw new IllegalStateException("웰스토리 페이지 조회가 1000회를 초과했습니다.");
		} while ("Y".equals(contYn) && !nextKey.isBlank());
		return rows;
	}

	private Long ensureSupplier(Map<String, Object> command) {
		Map<String, Object> supplier = new LinkedHashMap<>();
		supplier.put("supplier_code", "WELSTORY");
		supplier.put("supplier_name", "삼성웰스토리");
		supplier.put("user_id", text(command.get("user_id")));
		mapper.ensureSupplier(supplier);
		return mapper.supplierIdByCode(supplier);
	}

	private Map<String, Object> checkpoint(Long supplierId, Long siteId, String type) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("supplier_id", supplierId);
		p.put("supplier_site_id", siteId);
		p.put("sync_type", type);
		return p;
	}

	private String storageType(String code) {
		return switch (code) {
		case "01" -> "REFRIGERATED";
		case "02" -> "FROZEN";
		case "03" -> "ROOM";
		default -> null;
		};
	}

	private String first(JsonNode node, String... fields) {
		for (String field : fields) {
			String v = node.path(field).asText("").trim();
			if (!v.isBlank())
				return v;
		}
		return "";
	}

	private String required(Map<String, Object> map, String key) {
		String v = text(map.get(key));
		if (v.isBlank())
			throw new IllegalArgumentException(key + "은 필수입니다.");
		return v;
	}

	private String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	private String valueOr(String value, String fallback) {
		return value.isBlank() ? fallback : value;
	}

	private BigDecimal decimal(String value) {
		try {
			return new BigDecimal(value.replaceAll("[^0-9.-]", ""));
		} catch (Exception e) {
			return BigDecimal.ZERO;
		}
	}

	private BigDecimal positive(String value, BigDecimal fallback) {
		BigDecimal v = decimal(value);
		return v.signum() > 0 ? v : fallback;
	}

	private int integer(String value) {
		try {
			return Integer.parseInt(value.replaceAll("[^0-9-]", ""));
		} catch (Exception e) {
			return 0;
		}
	}

	private String date(String value, String fallback) {
		try {
			return LocalDate.parse(value, BASIC_DATE).atStartOfDay().toString();
		} catch (Exception e) {
			return fallback;
		}
	}

	private String endDate(String value) {
		try {
			return LocalDate.parse(value, BASIC_DATE).atTime(23, 59, 59).toString();
		} catch (Exception e) {
			return null;
		}
	}

	private String json(JsonNode node) {
		try {
			return objectMapper.writeValueAsString(node);
		} catch (Exception e) {
			return "{}";
		}
	}

	private String abbreviate(String value, int max) {
		String v = value == null ? "동기화 실패" : value;
		return v.length() <= max ? v : v.substring(0, max);
	}
}
