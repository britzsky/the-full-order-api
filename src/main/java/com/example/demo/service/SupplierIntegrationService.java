package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.SupplierIntegrationMapper;

@Service
public class SupplierIntegrationService {
	private final SupplierIntegrationMapper mapper;
	private final SupplierSiteService siteService;

	public SupplierIntegrationService(SupplierIntegrationMapper mapper, SupplierSiteService siteService) {
		this.mapper = mapper;
		this.siteService = siteService;
	}

	public List<Map<String, Object>> sites(Map<String, Object> params) {
		require(params, "account_id");
		return mapper.sites(params);
	}

	public List<Map<String, Object>> offers(Map<String, Object> params) {
		require(params, "account_id");
		params.putIfAbsent("delivery_date", java.time.LocalDate.now().toString());
		java.time.LocalDate deliveryDate = java.time.LocalDate.parse(params.get("delivery_date").toString());
		params.put("price_at", deliveryDate.toString());
		List<Map<String, Object>> rows = mapper.offers(params);
		for (Map<String, Object> row : rows) {
			row.put("suggested_order_qty", suggestedQuantity(row));
			Object synced = row.get("last_synced_at");
			boolean stale = synced == null;
			if (synced instanceof java.sql.Timestamp timestamp)
				stale = timestamp.toLocalDateTime().isBefore(java.time.LocalDateTime.now().minusHours(24));
			row.put("stale", stale);
			boolean orderable = "ACTIVE".equals(row.get("product_status")) && !"N".equals(row.get("orderable_yn"))
					&& row.get("account_ingredient_product_id") != null
					&& decimal(row.get("purchase_price")).signum() > 0 && decimal(row.get("base_qty")).signum() > 0;
			row.put("orderable_yn", orderable ? "Y" : "N");
			if (!orderable && (row.get("availability_reason") == null
					|| row.get("availability_reason").toString().isBlank())) {
				java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
				if (!"ACTIVE".equals(row.get("product_status"))) {
					String stop = java.util.Objects.toString(row.get("status_reason"), "").trim();
					reasons.add(switch (stop) {
					case "QA STOP" -> "품질 이슈로 공급 중단(QA STOP)";
					case "PERIOD STOP" -> "지정 기간 공급 중단(PERIOD STOP)";
					case "WEEKLY STOP" -> "특정 요일 공급 중단(WEEKLY STOP)";
					default -> stop.isBlank() ? "공급사 판매 중단" : "공급사 판매 중단: " + stop;
					});
				}
				if (decimal(row.get("purchase_price")).signum() <= 0)
					reasons.add("선택한 납품일의 공급사 가격 없음");
				if (row.get("account_ingredient_product_id") == null)
					reasons.add("거래처 공급상품 연결 없음");
				if (decimal(row.get("base_qty")).signum() <= 0)
					reasons.add("포장 기준용량 확인 필요");
				if (reasons.isEmpty())
					reasons.add("선택한 납품일에 발주할 수 없습니다.");
				row.put("availability_reason", String.join(" · ", reasons));
			}
			var decision = OrderAvailability.evaluate(row, deliveryDate);
			decision.apply(row);
			if ("UNAVAILABLE".equals(decision.status())) {
				row.put("orderable_yn", "N");
				row.put("availability_reason", decision.reason());
			}
			if ("REVIEW_REQUIRED".equals(decision.status())) row.put("availability_reason", decision.reason());
		}
		return rows;
	}

	public List<Map<String, Object>> syncStatus(Map<String, Object> params) {
		require(params, "account_id");
		return mapper.syncStatus(params);
	}

	public List<Map<String, Object>> receipts(Map<String, Object> params) {
		require(params, "account_id");
		return mapper.receipts(params);
	}

	public List<Map<String, Object>> incidents(Map<String, Object> params) {
		require(params, "account_id");
		return mapper.incidents(params);
	}

	@Transactional
	/** 거래처 사업장 지정은 매핑 규칙(거래처당 공급사별 1개, 공유 확인)을 거친다. */
	public Map<String, Object> saveSite(Map<String, Object> body) {
		require(body, "account_id", "supplier_code", "external_site_code");
		return siteService.assign(body.get("account_id").toString(), body.get("supplier_code").toString(),
				body.get("external_site_code").toString(), "Y".equals(body.get("allow_shared")),
				body.get("user_id") == null ? null : body.get("user_id").toString());
	}

	@Transactional
	public Map<String, Object> ingestOffer(Map<String, Object> body) {
		require(body, "supplier_account_site_id", "supplier_product_id", "purchase_price", "effective_from");
		positive(body, "minimum_order_qty", BigDecimal.ONE);
		positive(body, "order_increment_qty", BigDecimal.ONE);
		mapper.upsertOffer(body);
		require(body, "supplier_offer_id");
		mapper.closeOfferPrice(body);
		mapper.insertOfferPrice(body);
		if (body.get("delivery_date") != null)
			mapper.upsertAvailability(body);
		return Map.of("code", 200, "message", "success", "supplier_offer_id", body.get("supplier_offer_id"));
	}

	private BigDecimal suggestedQuantity(Map<String, Object> row) {
		BigDecimal shortage = decimal(row.get("shortage_base_qty"));
		BigDecimal baseQty = decimal(row.get("base_qty"));
		BigDecimal minimum = decimal(row.get("minimum_order_qty"));
		BigDecimal increment = decimal(row.get("order_increment_qty"));
		if (shortage.signum() <= 0 || baseQty.signum() <= 0)
			return BigDecimal.ZERO;
		BigDecimal orderUnits = shortage.divide(baseQty, 12, RoundingMode.CEILING);
		if (orderUnits.compareTo(minimum) < 0)
			orderUnits = minimum;
		if (increment.signum() > 0) {
			orderUnits = orderUnits.divide(increment, 0, RoundingMode.CEILING).multiply(increment);
		}
		return orderUnits.stripTrailingZeros();
	}

	private void positive(Map<String, Object> body, String field, BigDecimal fallback) {
		BigDecimal value = body.get(field) == null ? fallback : decimal(body.get(field));
		if (value.signum() <= 0)
			throw new IllegalArgumentException(field + " must be greater than zero.");
		body.put(field, value);
	}

	private BigDecimal decimal(Object value) {
		return value == null || value.toString().isBlank() ? BigDecimal.ZERO : new BigDecimal(value.toString());
	}

	private void require(Map<String, Object> params, String... fields) {
		for (String field : fields) {
			if (params.get(field) == null || params.get(field).toString().isBlank()) {
				throw new IllegalArgumentException(field + " is required.");
			}
		}
	}
}
