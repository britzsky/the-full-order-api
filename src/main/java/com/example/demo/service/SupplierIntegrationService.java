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
	private com.example.demo.mapper.FavoriteMapper favorites;

	public SupplierIntegrationService(SupplierIntegrationMapper mapper, SupplierSiteService siteService) {
		this.mapper = mapper;
		this.siteService = siteService;
	}

	@org.springframework.beans.factory.annotation.Autowired
	public void setFavorites(com.example.demo.mapper.FavoriteMapper favorites) { this.favorites = favorites; }

	/** 나만의 식재료 지정(favorite=Y)·해제(N). 주인(favorite_owner_*)은 컨트롤러가 로그인으로 정한다. */
	public Map<String, Object> favorite(Map<String, Object> body) {
		require(body, "supplier_product_id", "favorite_owner_type", "favorite_owner_id");
		if (favorites.productExists(body) == 0)
			throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
		boolean on = !"N".equals(String.valueOf(body.get("favorite")));
		if (on) favorites.addProduct(body); else favorites.removeProduct(body);
		return Map.of("code", 200, "message", "success", "supplier_product_id", body.get("supplier_product_id"), "favorite_yn", on ? "Y" : "N");
	}

	public List<Map<String, Object>> sites(Map<String, Object> params) {
		require(params, "account_id");
		return mapper.sites(params);
	}

	/** 검색어의 단어·정규화 전체·동의어를 SQL LIKE 패턴으로(소문자, 공백 제거, LIKE 특수문자 이스케이프) */
	static java.util.List<String> namePatterns(String keyword) {
		java.util.LinkedHashSet<String> words = new java.util.LinkedHashSet<>();
		var query = ProductNameMatch.query(keyword);
		if (!query.key().isEmpty()) words.add(query.key());
		words.addAll(query.aliases());
		for (String token : ProductNameMatch.tokens(keyword)) {
			String normalized = ProductNameMatch.normalize(token);
			if (!normalized.isEmpty()) { words.add(normalized); words.addAll(ProductNameMatch.query(token).aliases()); }
		}
		return words.stream().limit(20).map(word -> word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")).toList();
	}

	public List<Map<String, Object>> offers(Map<String, Object> params) {
		require(params, "account_id");
		// ingredient_ids는 서버 내부(부족량 계산)에서 목록으로만 넘긴다. 요청 파라미터 문자열이면 무시.
		if (params.containsKey("ingredient_ids") && !(params.get("ingredient_ids") instanceof java.util.Collection<?>))
			params.remove("ingredient_ids");
		if (params.containsKey("ingredient_name_patterns") && !(params.get("ingredient_name_patterns") instanceof java.util.Collection<?>))
			params.remove("ingredient_name_patterns");
		params.remove("name_patterns");
		// 화면 검색: keyword(동의어 포함)·offer_ids(쉼표 목록)·limit. 사업장 전체 상품(1만여 건, 수십 MB)을 내려보내지 않기 위함.
		String keyword = java.util.Objects.toString(params.get("keyword"), "").trim();
		if (keyword.length() > 100) throw new IllegalArgumentException("검색어는 100자 이하로 입력해주세요.");
		if (!keyword.isEmpty()) params.put("name_patterns", namePatterns(keyword));
		Object offerIds = params.get("offer_ids");
		if (offerIds != null && !(offerIds instanceof java.util.Collection<?>)) {
			java.util.List<Long> ids = new java.util.ArrayList<>();
			for (String id : offerIds.toString().split(","))
				if (id.trim().matches("\\d{1,18}")) ids.add(Long.valueOf(id.trim()));
			if (ids.isEmpty()) return new java.util.ArrayList<>();
			if (ids.size() > 500) throw new IllegalArgumentException("상품은 한 번에 500개까지 조회할 수 있습니다.");
			params.put("offer_ids", ids);
		}
		Integer limit = null;
		if (params.get("limit") != null && !params.get("limit").toString().isBlank()) {
			try { limit = Integer.valueOf(params.get("limit").toString().trim()); } catch (NumberFormatException e) { limit = -1; }
			if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit은 1~1000이어야 합니다.");
		}
		params.putIfAbsent("delivery_date", java.time.LocalDate.now().toString());
		java.time.LocalDate deliveryDate = java.time.LocalDate.parse(params.get("delivery_date").toString());
		params.put("price_at", deliveryDate.toString());
		List<Map<String, Object>> rows = mapper.offers(params);
		if (!keyword.isEmpty()) {
			// SQL은 넓게 걸렀으므로 화면과 같은 규칙으로 판정·관련도순 정렬
			java.util.Map<Map<String, Object>, Integer> scores = new java.util.IdentityHashMap<>();
			for (Map<String, Object> row : rows)
				scores.put(row, ProductNameMatch.searchScore(keyword, java.util.Objects.toString(row.get("product_name"), ""),
						java.util.Objects.toString(row.get("supplier_item_code"), ""), java.util.Objects.toString(row.get("supplier_name"), "")));
			// 나만의 식재료(하트)는 검색 결과 맨 위(2026-10-22), 그다음 관련도순
			rows = new java.util.ArrayList<>(rows.stream().filter(row -> scores.get(row) > 0)
					.sorted(java.util.Comparator.comparing((Map<String, Object> row) -> !"Y".equals(row.get("favorite_yn")))
							.thenComparing(row -> -scores.get(row))).toList());
		}
		if (limit != null && rows.size() > limit) rows = new java.util.ArrayList<>(rows.subList(0, limit));
		for (Map<String, Object> row : rows) {
			row.put("suggested_order_qty", suggestedQuantity(row));
			Object synced = row.get("last_synced_at");
			boolean stale = synced == null;
			if (synced instanceof java.sql.Timestamp timestamp)
				stale = timestamp.toLocalDateTime().isBefore(java.time.LocalDateTime.now().minusHours(24));
			row.put("stale", stale);
			boolean orderable = "ACTIVE".equals(row.get("product_status")) && !"N".equals(row.get("orderable_yn"))
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
		// 상품 검색 발주 화면만(guess_ingredient=Y): 담을 때 재고로 들어갈 식재료를 미리 맞혀 둔다. 부족량 계산 등 내부 호출은 하지 않음.
		if ("Y".equals(params.get("guess_ingredient")) && !rows.isEmpty() && rows.size() <= 1000) {
			Map<String, Object> query = new java.util.HashMap<>();
			query.put("account_id", params.get("account_id"));
			query.put("product_ids", rows.stream().map(row -> row.get("supplier_product_id")).filter(java.util.Objects::nonNull).distinct().toList());
			ProductIngredientGuess.apply(rows, ProductIngredientGuess.ingredients(mapper.ingredientGuessIngredients(query)),
					mapper.ingredientGuessFacts(query));
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
		require(body, "supplier_product_id", "purchase_price", "effective_from");
		// 상품·판가는 사업장 마스터 단위. 예전 요청(거래처 매핑 ID)도 받는다.
		if (body.get("supplier_site_id") == null || body.get("supplier_site_id").toString().isBlank()) {
			require(body, "supplier_account_site_id");
			Long siteId = mapper.siteIdOfMapping(body);
			if (siteId == null)
				throw new IllegalArgumentException("공급사 사업장 연결을 찾을 수 없습니다.");
			body.put("supplier_site_id", siteId);
		}
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
