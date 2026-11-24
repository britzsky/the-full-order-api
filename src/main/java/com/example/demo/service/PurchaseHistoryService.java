package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.PurchaseHistoryMapper;

/**
 * 구매 이력 활용(2026-11-19): 재고 시작일, 산 상품 목록(ABC), 상품 → 식재료 연결, 식재료별 단가 이력.
 * 연결은 공급사 상품 마스터가 아니라 입고 줄(tb_goods_receipt_item.ingredient_id)에 남긴다(DECISIONS 2026-10-22: 연결 대신 이력).
 * 원가 계산(MenuMapper.accountIngredientPlanningPrices)은 식재료가 연결된 최근 입고 줄의 상품 판가를 쓴다.
 */
@Service
public class PurchaseHistoryService {
	/** 같은 식재료 단가가 이만큼(%) 넘게 벌어지면 '단가 차이 큼'으로 표시한다 */
	static final BigDecimal SPREAD_ALERT_PCT = new BigDecimal("30");
	private final PurchaseHistoryMapper mapper;

	public PurchaseHistoryService(PurchaseHistoryMapper mapper) {
		this.mapper = mapper;
	}

	public Map<String, Object> settings(String accountId) {
		if (text(accountId).isBlank())
			throw new IllegalArgumentException("account_id는 필수입니다.");
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("account_id", accountId);
		m.put("inventory_start_date", mapper.inventoryStartDate(Map.of("account_id", accountId)));
		return m;
	}

	@Transactional
	public Map<String, Object> saveSettings(Map<String, Object> body) {
		String accountId = required(body, "account_id");
		String value = text(body.get("inventory_start_date"));
		LocalDate start = null;
		if (!value.isBlank()) {
			try {
				start = LocalDate.parse(value);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("재고 시작일은 YYYY-MM-DD 형식이어야 합니다.");
			}
		}
		Map<String, Object> p = new HashMap<>();
		p.put("account_id", accountId);
		p.put("inventory_start_date", start == null ? null : start.toString());
		p.put("user_id", text(body.get("user_id")));
		mapper.saveInventorySetting(p);
		int reclassified = mapper.reclassifyHistory(p);
		Map<String, Object> m = settings(accountId);
		m.put("reclassified_receipt_count", reclassified);
		return m;
	}

	/**
	 * 이 거래처가 산 공급사 상품을 식재료에 연결한다(재고에 넣지 않은 이력 줄 전체). ingredient_id를 비우면 연결을 푼다.
	 * 환산은 상품 규격 중 식재료 기준단위로 바꿀 수 있는 것, 안 되면 base_qty_per_order_unit(발주단위 1개당 식재료 기준수량)을 받는다.
	 */
	@Transactional
	public Map<String, Object> link(Map<String, Object> body) {
		String accountId = required(body, "account_id");
		String productId = required(body, "supplier_product_id");
		Map<String, Object> product = mapper.product(Map.of("supplier_product_id", productId));
		if (product == null)
			throw new IllegalArgumentException("공급사 상품을 찾을 수 없습니다.");
		String ingredientId = text(body.get("ingredient_id"));
		BigDecimal productQty = number(product.get("base_qty"));
		String unit;
		BigDecimal perUnit;
		if (ingredientId.isBlank()) {
			unit = productQty.signum() > 0 ? text(product.get("base_unit")) : text(product.get("order_unit"));
			perUnit = productQty.signum() > 0 ? productQty : BigDecimal.ONE;
		} else {
			Map<String, Object> ingredient = mapper.accountIngredient(Map.of("account_id", accountId, "ingredient_id", ingredientId));
			if (ingredient == null)
				throw new IllegalArgumentException("거래처 식재료를 찾을 수 없습니다: " + ingredientId);
			unit = QuantityUnits.unit(ingredient.get("base_unit"));
			String manual = text(body.get("base_qty_per_order_unit"));
			if (!manual.isBlank()) {
				perUnit = number(manual);
			} else {
				List<QuantityUnits.Pack> packs = List.of(
						new QuantityUnits.Pack(productQty, text(product.get("base_unit"))),
						new QuantityUnits.Pack(number(product.get("alt_base_qty")), text(product.get("alt_base_unit"))));
				QuantityUnits.Pack pack = QuantityUnits.packFor(packs, unit);
				if (pack == null)
					throw new IllegalArgumentException("상품 규격을 식재료 기준단위(" + unit + ")로 바꿀 수 없습니다 · 1포장당 수량 입력 요망: "
							+ text(product.get("product_name")));
				perUnit = QuantityUnits.convert(pack.quantity(), pack.baseUnit(), unit);
			}
			if (perUnit.signum() <= 0)
				throw new IllegalArgumentException("1포장당 식재료 수량은 0보다 커야 합니다.");
		}
		Map<String, Object> p = new HashMap<>();
		p.put("account_id", accountId);
		p.put("supplier_product_id", productId);
		p.put("ingredient_id", ingredientId.isBlank() ? null : ingredientId);
		p.put("base_unit", unit);
		p.put("per_unit", perUnit);
		p.put("only_unlinked", false);
		int updated = mapper.linkProductLines(p);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("supplier_product_id", productId);
		m.put("ingredient_id", ingredientId.isBlank() ? null : ingredientId);
		m.put("base_unit", unit);
		m.put("base_qty_per_order_unit", perUnit);
		m.put("updated_line_count", updated);
		return m;
	}

	/** 산 상품 목록(공급가액 큰 순)과 ABC 분류(누적 70%까지 A, 90%까지 B, 나머지 C) */
	public Map<String, Object> products(Map<String, Object> params) {
		Map<String, Object> p = query(params);
		p.put("unlinked_only", "Y".equalsIgnoreCase(text(params.get("unlinked_only"))));
		List<Map<String, Object>> rows = mapper.purchasedProducts(p);
		BigDecimal total = rows.stream().map(r -> number(r.get("total_supply_amount")).max(BigDecimal.ZERO))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal running = BigDecimal.ZERO;
		List<Map<String, Object>> result = new ArrayList<>();
		int a = 0, b = 0, c = 0;
		for (Map<String, Object> row : rows) {
			Map<String, Object> r = new LinkedHashMap<>(row);
			BigDecimal amount = number(row.get("total_supply_amount")).max(BigDecimal.ZERO);
			BigDecimal before = pct(running, total);
			running = running.add(amount);
			r.put("amount_share_pct", pct(amount, total));
			r.put("cumulative_share_pct", pct(running, total));
			// 앞 상품까지의 누적이 70% 미만이면 A — 첫 상품은 항상 A
			String grade = before.compareTo(new BigDecimal("70")) < 0 ? "A" : before.compareTo(new BigDecimal("90")) < 0 ? "B" : "C";
			r.put("abc_class", grade);
			switch (grade) {
			case "A" -> a++;
			case "B" -> b++;
			default -> c++;
			}
			result.add(r);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("products", result);
		m.put("product_count", result.size());
		m.put("total_supply_amount", total);
		m.put("abc_counts", Map.of("A", a, "B", b, "C", c));
		return m;
	}

	/** 식재료별 기준단위당 단가 요약. 최저·최고 차이가 가중평균의 30% 이상이면 price_varies=Y */
	public Map<String, Object> ingredientPrices(Map<String, Object> params) {
		Map<String, Object> p = query(params);
		p.put("ingredient_id", text(params.get("ingredient_id")));
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> row : mapper.ingredientPrices(p)) {
			Map<String, Object> r = new LinkedHashMap<>(row);
			BigDecimal avg = number(row.get("weighted_unit_cost"));
			BigDecimal spread = avg.signum() > 0
					? pct(number(row.get("max_unit_cost")).subtract(number(row.get("min_unit_cost"))), avg)
					: BigDecimal.ZERO;
			r.put("spread_pct", spread);
			r.put("price_varies", spread.compareTo(SPREAD_ALERT_PCT) >= 0 ? "Y" : "N");
			result.add(r);
		}
		return Map.of("ingredients", result, "spread_alert_pct", SPREAD_ALERT_PCT);
	}

	/** 식재료 한 개의 입고 줄별 단가와 가중평균 대비 차이(%) */
	public Map<String, Object> ingredientPriceLines(Map<String, Object> params) {
		Map<String, Object> p = query(params);
		p.put("ingredient_id", required(params, "ingredient_id"));
		List<Map<String, Object>> lines = mapper.ingredientPriceLines(p);
		BigDecimal qty = BigDecimal.ZERO, amount = BigDecimal.ZERO;
		for (Map<String, Object> line : lines) {
			qty = qty.add(number(line.get("base_received_qty")));
			amount = amount.add(number(line.get("supply_amount")));
		}
		BigDecimal avg = qty.signum() > 0 ? amount.divide(qty, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> line : lines) {
			Map<String, Object> r = new LinkedHashMap<>(line);
			BigDecimal cost = line.get("unit_cost") == null ? null : number(line.get("unit_cost"));
			BigDecimal deviation = cost == null || avg.signum() == 0 ? null : pct(cost.subtract(avg), avg);
			r.put("deviation_pct", deviation);
			r.put("price_outlier", deviation != null && deviation.abs().compareTo(SPREAD_ALERT_PCT) >= 0 ? "Y" : "N");
			result.add(r);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ingredient_id", p.get("ingredient_id"));
		m.put("weighted_unit_cost", avg);
		m.put("lines", result);
		return m;
	}

	private Map<String, Object> query(Map<String, Object> params) {
		Map<String, Object> p = new HashMap<>();
		p.put("account_id", required(params, "account_id"));
		for (String key : List.of("from_date", "to_date")) {
			String v = text(params.get(key));
			if (!v.isBlank()) {
				try {
					LocalDate.parse(v);
				} catch (RuntimeException e) {
					throw new IllegalArgumentException(key + "는 YYYY-MM-DD 형식이어야 합니다.");
				}
			}
			p.put(key, v);
		}
		p.put("supplier_code", text(params.get("supplier_code")).toUpperCase(Locale.ROOT));
		return p;
	}

	private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
		return whole.signum() == 0 ? BigDecimal.ZERO
				: part.multiply(new BigDecimal("100")).divide(whole, 1, RoundingMode.HALF_UP);
	}

	static BigDecimal number(Object value) {
		if (value == null)
			return BigDecimal.ZERO;
		try {
			return new BigDecimal(value.toString().trim());
		} catch (RuntimeException e) {
			return BigDecimal.ZERO;
		}
	}

	private static String required(Map<String, Object> map, String key) {
		String v = text(map.get(key));
		if (v.isBlank())
			throw new IllegalArgumentException(key + "은 필수입니다.");
		return v;
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}
}
