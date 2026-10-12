package com.example.demo.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 내부 발주를 먼저 확정 저장하고 외부 결과가 불명확한 요청은 재전송하지 않는다. */
@Service
public class OrderWorkflowService {
	private final OrderWorkflowMapper mapper;
	private final SupplierIntegrationMapper catalog;
	private final WelstoryItemLookupService api;
	private final ObjectMapper json;
	private final TransactionTemplate tx;
	private final ShortageProcurementService shortages;
    private OurhomeOrderAdapter ourhome;
    @org.springframework.beans.factory.annotation.Autowired
    public void setOurhome(OurhomeOrderAdapter ourhome) { this.ourhome = ourhome; }

	public OrderWorkflowService(OrderWorkflowMapper mapper, SupplierIntegrationMapper catalog,
			WelstoryItemLookupService api, ObjectMapper json, PlatformTransactionManager manager,
			ShortageProcurementService shortages) {
		this.mapper = mapper;
		this.catalog = catalog;
		this.api = api;
		this.json = json;
		this.shortages = shortages;
		this.tx = new TransactionTemplate(manager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public Map<String, Object> list(Map<String, Object> input) {
		Map<String, Object> p = new LinkedHashMap<>(input);
		required(p, "account_id");
		p.put("limit", Math.min(200, Math.max(1, Integer.parseInt(text(p.getOrDefault("limit", 50))))));
		p.put("offset", Math.max(0, Integer.parseInt(text(p.getOrDefault("offset", 0)))));
		for (String field : List.of("date_from", "date_to"))
			if (!text(p.get(field)).isBlank())
				LocalDate.parse(text(p.get(field)));
		List<Map<String, Object>> orders = mapper.orders(p);
		for (Map<String, Object> order : orders)
			order.put("items", mapper.items(order));
		for (Map<String, Object> order : orders) order.put("preference_changes", preferenceChanges(order));
		return Map.of("orders", orders, "limit", p.get("limit"), "offset", p.get("offset"));
	}

	/** 동일 요청번호는 기존 결과를 반환하며 다른 내용으로 재사용할 수 없다. */
	public Map<String, Object> order(JsonNode payload) {
		JsonNode header = payload.path("order"), items = payload.path("items");
		if (!items.isArray() || items.isEmpty())
			throw new IllegalArgumentException("발주 품목이 필요합니다.");
		String account = required(header, "account_id"), site = required(header, "sold_to");
		String supplier = header.path("supplier_code").asText("WELSTORY");
        if (!Set.of("WELSTORY", "OURHOME").contains(supplier)) throw new IllegalArgumentException("지원하지 않는 공급사입니다.");
        boolean isOurhome = "OURHOME".equals(supplier);
        String requestKey = header.path("request_key").asText("");
		String client = header.path("client_ord").asText("");
		if (!requestKey.isBlank() && !requestKey.matches("[A-Za-z0-9-]{16,64}"))
			throw new IllegalArgumentException("주문 요청키가 올바르지 않습니다.");
		String day = required(header, "req_delivery_date");
		if (!day.matches("\\d{8}"))
			throw new IllegalArgumentException("납품일은 yyyyMMdd 형식이어야 합니다.");
		if (!isOurhome && !site.matches("[A-Za-z0-9]{8}"))
			throw new IllegalArgumentException("웰스토리 사업장 코드는 8자리입니다.");
		if (isOurhome && (requestKey.isBlank() || !site.matches("[A-Za-z0-9]{1,50}")))
            throw new IllegalArgumentException("아워홈 사업장과 주문 요청키를 확인하세요.");
        if (!isOurhome && requestKey.isBlank() && !client.matches("7" + day.substring(2) + site + "[A-Za-z0-9]{2}"))
			throw new IllegalArgumentException("주문번호 형식이 올바르지 않습니다.");
		LocalDate date = LocalDate.parse(day, DateTimeFormatter.BASIC_ISO_DATE);
		String id = UUID
				.nameUUIDFromBytes((account + "|" + supplier + "|" + site + "|" + (requestKey.isBlank() ? client : requestKey))
						.getBytes(StandardCharsets.UTF_8))
				.toString();
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("account_id", account);
		p.put("purchase_order_id", id);
		Map<String, Object> existing = mapper.order(p);
		if (existing != null)
			return existingResult(existing, items, day);
		if (date.isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))))
			throw new IllegalArgumentException("지난 납품일로 발주할 수 없습니다.");
		p.put("supplier_code", supplier);
		p.put("sold_to", site);
		p.put("client_ord", isOurhome ? id : client);
		p.put("requested_delivery_date", date.toString());
		p.put("client_note", header.path("client_note").asText(""));
		p.put("user_id", header.path("user_id").asText(""));
		List<Map<String, Object>> rows = new ArrayList<>();
		Set<String> ingredientIds = new HashSet<>();
		BigDecimal total = BigDecimal.ZERO;
		for (JsonNode item : items) {
			String code = item.hasNonNull("supplier_item_code") ? required(item, "supplier_item_code") : required(item, "welstory_item_code");
			Map<String, Object> query = new LinkedHashMap<>();
			query.put("account_id", account);
			query.put("delivery_date", date.toString());
			query.put("price_at", date.toString());
			query.put("external_site_code", site);
			query.put("supplier_code", supplier);
			query.put("supplier_item_code", code);
			List<Map<String, Object>> offers = catalog.offers(query);
			if (offers.size() != 1)
				throw new IllegalArgumentException("구매 가능한 사업장 상품 연결을 확인하세요: " + code);
			Map<String, Object> row = new LinkedHashMap<>(offers.get(0));
			OrderAvailability.requireAvailable(row, date, item.path("delivery_terms_confirmed").asBoolean(false));
			row.put("delivery_terms_confirmed", item.path("delivery_terms_confirmed").asBoolean(false));
			String ingredientId = text(row.get("ingredient_id"));
			if (!ingredientId.isBlank() && !ingredientIds.add(ingredientId))
				throw new IllegalArgumentException("동일 식재료는 합산하여 한 번만 발주해주세요: " + ingredientId);
			if (!"ACTIVE".equals(row.get("product_status"))
					|| "N".equals(row.get("orderable_yn")))
				throw new IllegalArgumentException("주문 중단 또는 미연결 상품입니다: " + code);
			BigDecimal qty = number(required(item, "order_qty")), price = number(row.get("purchase_price"));
			validateQuantity(qty, number(row.get("minimum_order_qty")), number(row.get("order_increment_qty")),
					text(row.get("decimal_order_allowed")));
			if (price.signum() <= 0 || number(row.get("base_qty")).signum() <= 0)
				throw new IllegalArgumentException("가격·단위 환산을 확인하세요: " + code);
			JsonNode data;
            if (isOurhome) {
                data = ourhome.validate(site, date.toString(), code, row, qty);
            } else {
            ObjectNode lookup = json.createObjectNode();
			lookup.putObject("dataHeader").put("soldTo", site).put("itemCode", code).put("reqDeliveryDate", day);
			lookup.putObject("dataBody");
			JsonNode live = api.call("/fdapi/service/payer-realtime-item", lookup);
			if (!"S0000".equals(live.path("dataBody").path("resCd").asText()))
				throw new IllegalArgumentException("실시간 상품 검증에 실패했습니다: " + code);
			data = live.path("dataBody").path("data");
			if (data.isArray()) {
				if (data.size() != 1)
					throw new IllegalArgumentException("실시간 상품 응답을 확인하세요: " + code);
				data = data.get(0);
			}
			}
			if (!data.isObject() || data.isEmpty())
				throw new IllegalArgumentException("실시간 상품 정보가 없습니다: " + code);
			if (!data.path("stopType").asText("").isBlank())
				throw new IllegalArgumentException("주문 중지 상품입니다: " + code);
			if (!data.path("itemCode").asText(code).equals(code))
				throw new IllegalArgumentException("상품 응답 코드가 다릅니다.");
			if (data.hasNonNull("price") && number(data.path("price").asText()).compareTo(price) != 0)
				throw new IllegalArgumentException("가격이 변경되었습니다. 상품 정보를 동기화한 후 다시 확인하세요: " + code);
			if (item.hasNonNull("expected_price") && number(item.path("expected_price").asText()).compareTo(price) != 0)
				throw new IllegalArgumentException("화면의 가격이 변경되었습니다. 다시 조회하세요: " + code);
			if (data.hasNonNull("minQntty") && data.hasNonNull("orderIncrs"))
				validateQuantity(qty, number(data.path("minQntty").asText()), number(data.path("orderIncrs").asText()),
						data.path("decYN").asText("N"));
			row.put("purchase_order_id", id);
			row.put("account_id", account);
			row.put("user_id", header.path("user_id").asText(""));
			row.put("source_ingredient_id", item.path("source_ingredient_id").asText(item.path("ingredient_id").asText("")));
			row.put("product_name_snapshot", row.get("product_name"));
			ObjectNode snapshot = json.valueToTree(row);
			snapshot.set("provider_realtime_product", data.deepCopy());
			row.put("product_snapshot", snapshot.toString());
			row.put("client_ord_item", String.valueOf(rows.size() + 1));
			row.put("menu_id", item.path("menu_id").asText(""));
			row.put("order_qty", qty);
			BigDecimal amount = qty.multiply(price).setScale(2, java.math.RoundingMode.HALF_UP);
			row.put("line_amount", amount);
			rows.add(row);
			total = total.add(amount);
		}
		p.put("supplier_id", rows.get(0).get("supplier_id"));
		p.put("total_amount", total);
		ObjectNode request = isOurhome ? ourhome.request(p, rows) : json.createObjectNode();
		if (!isOurhome) {
		request.putObject("dataHeader").put("clientOrd", client).put("soldTo", site).put("reqDeliveryDate", day)
				.put("ordStatus", "N").put("clientNote", text(p.get("client_note")));
		var details = request.putObject("dataBody").putArray("ordDetail");
		for (var row : rows)
			details.addObject().put("clientOrd", client).put("clientOrdItem", text(row.get("client_ord_item")))
					.put("itemCode", text(row.get("supplier_item_code"))).put("ordQty", text(row.get("order_qty")))
					.put("specialNote", "").put("itemDeliveryDate", day).put("ordItemStatus", "N");
		}
		p.put("request_payload", request.toString());
		p.put("fingerprint", digest(request.toString()));
		try {
			tx.executeWithoutResult(s -> {
				// Serialize reservations so another request cannot reuse the same shortage.
				mapper.lockSupplier(p);
				for (Map<String,Object> row : rows) OrderAvailability.requireAvailable(row,date,Boolean.TRUE.equals(row.get("delivery_terms_confirmed")));
				for (Map<String, Object> row : rows) prepareProduct(row);
				if (header.hasNonNull("shortage_criteria")) {
					Map<String, Object> context = json.convertValue(header.path("shortage_criteria"), Map.class);
					context.put("account_id", account);
					context.put("sold_to", site);
					context.put("supplier_code", supplier);
					context.put("delivery_date", date.toString());
					shortages.validateSubmission(context, rows);
				}
				if (!isOurhome && !requestKey.isBlank()) {
					Set<String> used = new HashSet<>(mapper.clientOrderKeys(p));
					String allocated = null;
					for (int sequence = 1; sequence < 1296; sequence++) {
						String suffix = Integer.toString(sequence, 36).toUpperCase(Locale.ROOT);
						String candidate = "7" + day.substring(2) + site + (suffix.length() == 1 ? "0" : "") + suffix;
						if (!used.contains(candidate)) {
							allocated = candidate;
							break;
						}
					}
					if (allocated == null)
						throw new IllegalArgumentException("해당 납품일의 주문번호를 모두 사용했습니다.");
					p.put("client_ord", allocated);
					((ObjectNode) request.path("dataHeader")).put("clientOrd", allocated);
					for (JsonNode detail : request.path("dataBody").path("ordDetail"))
						((ObjectNode) detail).put("clientOrd", allocated);
					p.put("request_payload", request.toString());
					p.put("fingerprint", digest(request.toString()));
				}
				mapper.insertOrder(p);
				rows.forEach(mapper::insertItem);
				mapper.insertExchange(p);
			});
		} catch (DuplicateKeyException e) {
			existing = mapper.order(p);
			if (existing == null)
				throw e;
			return existingResult(existing, items, day);
		}
		JsonNode response;
		try {
			response = isOurhome ? ourhome.submit(request, p, rows) : api.call("/fdapi/service/payer-soldto-order", request);
		} catch (RuntimeException e) {
			// 응답 유실(시간 초과·5xx 등)은 접수 여부를 알 수 없으므로 재전송하지 않는다. 원인 종류는 대사·문의용으로 남긴다.
			String reason = e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().length() <= 200 ? " " + e.getMessage() : "";
			finishUnknown(p, ("응답을 확인하지 못했습니다." + reason + " 발주서에서 주문 대사 후 확인하세요.").trim());
			return result(p, "UNKNOWN", true);
		}
		try {
			recordResponse(p, rows, response, false);
		} catch (RuntimeException e) {
			finishUnknown(p, "외부 응답 저장을 확인하지 못했습니다. 같은 주문을 다시 전송하지 말고 대사하세요.");
			return result(p, "UNKNOWN", true);
		}
		return result(p, text(p.get("status")), false);
	}

	public Map<String, Object> reconcile(Map<String, Object> input) {
		required(input, "account_id");
		required(input, "purchase_order_id");
		Map<String, Object> p = mapper.order(input);
		if (p == null)
			throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
		if ("OURHOME".equals(p.get("supplier_code"))) {
            var rows = mapper.items(p);
            JsonNode response = ourhome.reconcile(p, rows);
            if (!response.path("dataBody").path("data").isEmpty()) recordResponse(p, rows, response, true);
            else if (notFoundLongEnough(p)) recordResponse(p, rows, notFoundResponse(p, rows), true);
            return result(p, text(p.get("status")), false);
        }
        if (!"WELSTORY".equals(p.get("supplier_code")))
			throw new IllegalArgumentException("해당 공급사 주문 조회 연동을 확인하세요.");
		ObjectNode request = json.createObjectNode();
		request.putObject("dataHeader").put("soldTo", text(p.get("sold_to")))
				.put("reqDeliveryDate", text(p.get("requested_delivery_date")).replace("-", ""))
				.put("clientOrd", text(p.get("client_ord")));
		request.putObject("dataBody");
		JsonNode response = api.call("/fdapi/service/payer-order-list", request);
		String queryCode = response.path("dataBody").path("resCd").asText("");
		JsonNode queryData = response.path("dataBody").path("data");
		if (!"S0000".equals(queryCode) || !queryData.isArray() || queryData.isEmpty()) {
			// A failed/empty lookup is not new evidence about whether the order was
			// accepted.
			Map<String, Object> result = new LinkedHashMap<>(result(p, text(p.get("status")), false));
			result.put("query_result_code", queryCode);
			result.put("query_message",
					response.path("dataBody").path("resMsg").asText("조회 결과가 없습니다.") + " 기존 주문 처리 상태와 최초 응답을 유지합니다.");
			return result;
		}
		recordResponse(p, mapper.items(p), response, true);
		return result(p, text(p.get("status")), false);
	}

	public Map<String, Object> markNotReceived(Map<String, Object> input) {
		required(input, "account_id");
		required(input, "purchase_order_id");
		return tx.execute(s -> {
			Map<String, Object> p = mapper.lockOrder(input);
			if (p == null)
				throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
			if (!Set.of("ORDERED", "CONFIRMED", "NOT_RECEIVED").contains(text(p.get("status")))
					|| mapper.items(p).stream().anyMatch(i -> number(i.get("received_order_qty")).signum() > 0))
				throw new IllegalArgumentException("기입고 품목이 있거나 주문 결과가 확인되지 않았습니다.");
			p.put("status", "NOT_RECEIVED");
			p.put("supplier_result_code", null);
			p.put("supplier_result_message", "사용자가 미입고를 확인했습니다.");
			mapper.updateOrder(p);
			return result(p, "NOT_RECEIVED", false);
		});
	}

	/** 품목 순서가 바뀌어도 외부 품목키로 연결하며 확인되지 않은 품목은 성공으로 간주하지 않는다. */
	private void recordResponse(Map<String, Object> p, List<Map<String, Object>> rows, JsonNode response,
			boolean reconcile) {
		String code = response.path("dataBody").path("resCd").asText("");
		JsonNode data = response.path("dataBody").path("data");
		int success = 0, failed = 0;
		for (var row : rows) {
			JsonNode match = null;
			if (data.isArray())
				for (JsonNode candidate : data) {
					if (!candidate.path("clientOrd").asText(text(p.get("client_ord")))
							.equals(text(p.get("client_ord"))))
						continue;
					if (candidate.path("clientOrdItem").asText("").replaceFirst("^0+(?!$)", "")
							.equals(text(row.get("client_ord_item")))) {
						match = candidate;
						break;
					}
				}
			if (reconcile && match == null) {
				String previous = text(row.get("item_status"));
				if (Set.of("ORDERED", "CONFIRMED", "RECEIVED", "PARTIAL_RECEIVED").contains(previous))
					success++;
				if ("FAILED".equals(previous))
					failed++;
				continue;
			}
			String lineCode = match == null ? "" : match.path("resCd").asText("");
			boolean identityMatches = match != null && match.path("itemCode")
					.asText(text(row.get("supplier_item_code"))).equals(text(row.get("supplier_item_code")));
			boolean quantityMatches = match != null && (!match.hasNonNull("ordQty")
					|| number(match.path("ordQty").asText()).compareTo(number(row.get("order_qty"))) == 0);
			boolean accepted = "S0000".equals(code) && identityMatches && quantityMatches
					&& match.path("provider_verified").asBoolean(true)
					&& !"D".equals(match.path("ordItemStatus").asText())
					&& (Set.of("S0000", "S").contains(lineCode) || (reconcile && lineCode.isBlank()));
			boolean rejected = !lineCode.isBlank() && !Set.of("S0000", "S").contains(lineCode);
			String status = accepted ? (reconcile ? "CONFIRMED" : "ORDERED") : rejected ? "FAILED" : "UNKNOWN";
			if (accepted)
				success++;
			if (rejected)
				failed++;
			row.put("item_status", status);
			row.put("supplier_result_code", lineCode);
			row.put("supplier_error_message", match == null ? "외부 품목 결과 확인 필요" : match.path("errorMsg").asText(""));
		}
		String status = success == rows.size() ? (reconcile ? "CONFIRMED" : "ORDERED")
				: failed == rows.size() ? "FAILED" : success > 0 ? "PARTIAL_FAILED" : "UNKNOWN";
		p.put("status", status);
		p.put("supplier_result_code", code);
		p.put("supplier_result_message", response.path("dataBody").path("resMsg").asText(""));
		p.put("response_payload", response.toString());
		tx.executeWithoutResult(s -> {
			mapper.lockOrder(p);
			rows.forEach(mapper::updateItem);
			for (var row : mapper.items(p)) {
				if (Set.of("ORDERED", "CONFIRMED").contains(text(row.get("item_status")))) {
					row.put("account_id", p.get("account_id"));
					new OrderProductLinking(mapper, json).apply(row, true);
				}
			}
			mapper.updateOrder(p);
			if (!reconcile)
				mapper.finishExchange(p);
            else
                mapper.insertReconciliation(p);
		});
	}

	/** 아워홈 주문 조회에 없는 접수 불명확 주문을 미접수로 확정하기 전 대기 시간(공급사 반영 지연 대비). */
	static final java.time.Duration NOT_FOUND_GRACE = java.time.Duration.ofMinutes(5);

	/** 접수 불명확(UNKNOWN/SENDING) 주문이 전송 후 대기 시간이 지났는지. 조회는 성공했는데 공급사 주문 목록에 없을 때만 쓴다. */
	static boolean notFoundLongEnough(Map<String, Object> p) {
		if (!Set.of("UNKNOWN", "SENDING").contains(text(p.get("status")))) return false;
		Object at = p.get("ordered_at") == null ? p.get("created_at") : p.get("ordered_at");
		if (at == null) return false;
		try {
			LocalDateTime ordered = at instanceof LocalDateTime time ? time
					: at instanceof java.sql.Timestamp stamp ? stamp.toLocalDateTime()
					: LocalDateTime.parse(at.toString().replace(' ', 'T'));
			return ordered.plus(NOT_FOUND_GRACE).isBefore(LocalDateTime.now());
		} catch (RuntimeException e) { return false; }
	}

	/** 공급사 주문 조회에 없어 미접수로 확정하는 품목별 결과(대사). */
	private JsonNode notFoundResponse(Map<String, Object> p, List<Map<String, Object>> rows) {
		ObjectNode response = json.createObjectNode();
		var body = response.putObject("dataBody").put("resCd", "NOT_FOUND").put("resMsg", "공급사 주문 조회에 없음");
		var data = body.putArray("data");
		for (var row : rows)
			data.addObject().put("clientOrd", text(p.get("client_ord"))).put("clientOrdItem", text(row.get("client_ord_item")))
					.put("itemCode", text(row.get("supplier_item_code"))).put("resCd", "NOT_FOUND")
					.put("errorMsg", "아워홈 주문 조회에 없어 미접수로 처리했습니다. 필요하면 다시 발주하세요.");
		return response;
	}

	private void finishUnknown(Map<String, Object> p, String message) {
		p.put("status", "UNKNOWN");
		p.put("supplier_result_code", "UNKNOWN");
		p.put("supplier_result_message", message);
		p.put("response_payload", null);
		tx.executeWithoutResult(s -> {
			mapper.updateOrder(p);
			mapper.finishExchange(p);
		});
	}

	private Map<String, Object> existingResult(Map<String, Object> p, JsonNode requested, String day) {
		List<Map<String, Object>> rows = mapper.items(p);
		if (rows.size() != requested.size() || !text(p.get("requested_delivery_date")).replace("-", "").equals(day))
			throw new IllegalArgumentException("동일 주문번호의 요청 내용이 다릅니다.");
		for (int i = 0; i < rows.size(); i++)
			if (!text(rows.get(i).get("supplier_item_code"))
					.equals(requested.get(i).path("supplier_item_code").asText(requested.get(i).path("welstory_item_code").asText()))
					|| number(rows.get(i).get("order_qty"))
							.compareTo(number(requested.get(i).path("order_qty").asText())) != 0
					|| !text(rows.get(i).get("source_ingredient_id")).equals(requested.get(i).path("source_ingredient_id")
						.asText(requested.get(i).path("ingredient_id").asText(""))))
				throw new IllegalArgumentException("동일 주문번호의 상품·수량이 다릅니다.");
		return result(p, text(p.get("status")), true);
	}

	private Map<String, Object> result(Map<String, Object> p, String status, boolean replay) {
		boolean unresolved = Set.of("UNKNOWN", "SENDING").contains(status);
		if ("PARTIAL_FAILED".equals(status)) {
			var items = mapper.items(p);
			unresolved = items.isEmpty() || items.stream().anyMatch(i -> !Set.of("ORDERED", "CONFIRMED", "FAILED",
					"RECEIVED", "PARTIAL_RECEIVED", "NOT_RECEIVED", "CANCELED").contains(text(i.get("item_status"))));
		}
		return Map.of("purchase_order_id", p.get("purchase_order_id"), "status", status, "replayed", replay, "message",
				text(p.get("supplier_result_message")), "requires_reconciliation", unresolved,
				"preference_changes", preferenceChanges(p));
	}

	private List<Map<String,Object>> preferenceChanges(Map<String,Object> p) {
		List<Map<String,Object>> changes = new ArrayList<>();
		var linking = new OrderProductLinking(mapper, json);
		for (var row : mapper.items(p)) {
			var change = linking.snapshot(row).path("automatic_preference");
			if (change.isObject()) changes.add(Map.of("client_ord_item", text(row.get("client_ord_item")),
				"ingredient_id", text(row.get("source_ingredient_id")), "product_name", text(row.get("product_name")),
				"undone", change.path("undone").asBoolean()));
		}
		return changes;
	}

	public Map<String,Object> undoPreference(Map<String,Object> input) {
		required(input, "account_id"); required(input, "purchase_order_id"); required(input, "client_ord_item");
		return tx.execute(status -> {
			if (mapper.lockOrder(input) == null) throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
			var row = mapper.items(input).stream().filter(item -> text(item.get("client_ord_item")).equals(text(input.get("client_ord_item"))))
				.findFirst().orElseThrow(() -> new IllegalArgumentException("발주 품목을 찾을 수 없습니다."));
			new OrderProductLinking(mapper, json).undo(row);
			return Map.of("code", 200, "message", "이전 우선 상품으로 되돌렸습니다.");
		});
	}

	private void prepareProduct(Map<String, Object> row) {
		row.put("ingredient_link_status", "NONE");
		if (!text(row.get("source_ingredient_id")).isBlank()) {
			Map<String, Object> source = mapper.lockSourceIngredient(row);
			if (source == null || source.isEmpty()) throw new IllegalArgumentException("거래처의 원본 식자재를 찾을 수 없습니다.");
			var packs = new ArrayList<QuantityUnits.Pack>();
			packs.add(new QuantityUnits.Pack(number(row.get("base_qty")), text(row.get("base_unit"))));
			packs.add(new QuantityUnits.Pack(number(row.get("alt_base_qty")), text(row.get("alt_base_unit"))));
			var pack = QuantityUnits.packFor(packs, source.get("base_unit"));
			if (pack == null) throw new IllegalArgumentException("상품을 식재료 기준단위로 환산할 수 없습니다.");
			row.put("base_qty", pack.quantity()); row.put("base_unit", pack.baseUnit());
			row.put("ingredient_link_status", "PENDING");
		}
		mapper.ensureAccountProduct(row);
		Map<String, Object> product = mapper.lockAccountProduct(row);
		if (product == null) throw new IllegalArgumentException("거래처 상품을 등록하지 못했습니다.");
		row.put("account_ingredient_product_id", product.get("account_ingredient_product_id"));
		row.put("target_mapping_snapshot", product.get("mapped_ingredient_id"));
		var context = "PENDING".equals(row.get("ingredient_link_status")) ? mapper.linkContext(row) : List.<Map<String,Object>>of();
		row.put("link_context_snapshot", "PENDING".equals(row.get("ingredient_link_status")) ? linkContextFingerprint(context) : null);
		if ("PENDING".equals(row.get("ingredient_link_status"))) {
			var stockUnits = mapper.productStockUnits(row);
			if (!stockUnits.isEmpty()) {
				String stockUnit = QuantityUnits.unit(stockUnits.get(0));
				if (stockUnits.stream().anyMatch(unit -> !QuantityUnits.unit(unit).equals(stockUnit)))
					throw new IllegalArgumentException("기존 재고의 단위를 확인해주세요.");
				row.put("base_qty", QuantityUnits.convert(number(row.get("base_qty")), row.get("base_unit"), stockUnit));
				row.put("base_unit", stockUnit);
			}
			ObjectNode snapshot = new OrderProductLinking(mapper, json).snapshot(row);
			snapshot.set("link_context", json.valueToTree(context.stream().map(link -> {
				Map<String,String> values = new LinkedHashMap<>();
				link.forEach((key,value) -> values.put(key, text(value)));
				return values;
			}).toList()));
			snapshot.put("base_qty", number(row.get("base_qty"))); snapshot.put("base_unit", text(row.get("base_unit")));
			row.put("product_snapshot", snapshot.toString());
		}
		if ("PENDING".equals(row.get("ingredient_link_status")) && !text(product.get("mapped_ingredient_id")).isBlank()
				&& !text(product.get("mapped_ingredient_id")).equals(text(row.get("source_ingredient_id"))))
			throw new IllegalArgumentException("선택 상품은 다른 식자재에 연결되어 있습니다. 상품 연결을 먼저 확인해주세요.");
	}

	static String linkContextFingerprint(List<Map<String, Object>> rows) {
		StringBuilder context = new StringBuilder();
		for (Map<String, Object> row : rows)
			for (String key : List.of("account_ingredient_product_id", "supplier_product_id", "mapped_ingredient_id", "active_yn", "preferred_yn", "mod_at")) {
				String value = text(row.get(key));
				context.append(value.length()).append(':').append(value).append(';');
			}
		return digest(context.toString());
	}

	static void validateQuantity(BigDecimal qty, BigDecimal minimum, BigDecimal increment, String decimalAllowed) {
		if (qty.signum() <= 0 || qty.scale() > 3 || minimum.signum() <= 0 || increment.signum() <= 0
				|| qty.compareTo(minimum) < 0 || qty.remainder(increment).signum() != 0
				|| (!"Y".equals(decimalAllowed) && qty.stripTrailingZeros().scale() > 0))
			throw new IllegalArgumentException("최소수량·발주배수·소수점 허용 조건을 확인하세요.");
	}

	static String text(Object o) {
		return o == null ? "" : o.toString();
	}

	static BigDecimal number(Object o) {
		return text(o).isBlank() ? BigDecimal.ZERO : new BigDecimal(text(o));
	}

	static String required(JsonNode n, String f) {
		String v = n.path(f).asText("").trim();
		if (v.isEmpty())
			throw new IllegalArgumentException(f + "은 필수입니다.");
		return v;
	}

	static String required(Map<String, Object> p, String f) {
		String v = text(p.get(f)).trim();
		if (v.isEmpty())
			throw new IllegalArgumentException(f + "은 필수입니다.");
		return v;
	}

	private static String digest(String value) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
