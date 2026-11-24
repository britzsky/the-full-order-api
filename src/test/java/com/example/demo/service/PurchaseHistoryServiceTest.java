package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.PurchaseHistoryMapper;

/** 구매 이력 활용: 상품 → 식재료 연결(기준단위 환산), ABC 분류, 단가 차이 표시, 재고 시작일 */
class PurchaseHistoryServiceTest {
	final PurchaseHistoryMapper mapper = mock(PurchaseHistoryMapper.class);
	final PurchaseHistoryService service = new PurchaseHistoryService(mapper);

	void product(String baseQty, String baseUnit, String altQty, String altUnit) {
		var p = new HashMap<String, Object>(Map.of("supplier_product_id", 100L, "product_name", "양파 1KG/망", "order_unit", "망",
				"base_qty", new BigDecimal(baseQty), "base_unit", baseUnit));
		if (altQty != null) {
			p.put("alt_base_qty", new BigDecimal(altQty));
			p.put("alt_base_unit", altUnit);
		}
		when(mapper.product(anyMap())).thenReturn(p);
	}

	void ingredient(String unit) {
		when(mapper.accountIngredient(anyMap())).thenReturn(Map.of("ingredient_id", "ONION", "base_unit", unit, "ingredient_name", "양파"));
	}

	Map<String, Object> body() {
		return new HashMap<>(Map.of("account_id", "A1", "supplier_product_id", "100", "ingredient_id", "ONION"));
	}

	@Test
	void linkConvertsProductPackToIngredientUnit() {
		product("1000", "g", null, null);
		ingredient("kg");
		when(mapper.linkProductLines(anyMap())).thenReturn(12);
		var result = service.link(body());
		verify(mapper).linkProductLines(argThat(p -> "ONION".equals(p.get("ingredient_id")) && "kg".equals(p.get("base_unit"))
				&& new BigDecimal("1").compareTo((BigDecimal) p.get("per_unit")) == 0 && Boolean.FALSE.equals(p.get("only_unlinked"))));
		assertThat(result).containsEntry("updated_line_count", 12);
	}

	@Test
	void linkUsesSecondSpecWhenFirstIsOtherDimension() {
		product("1560", "g", "30", "EA");
		ingredient("EA");
		service.link(body());
		verify(mapper).linkProductLines(argThat(p -> "EA".equals(p.get("base_unit")) && new BigDecimal("30").compareTo((BigDecimal) p.get("per_unit")) == 0));
	}

	@Test
	void linkNeedsManualQtyWhenSpecCannotConvert() {
		product("0", "망", null, null);
		ingredient("g");
		assertThatThrownBy(() -> service.link(body())).hasMessageContaining("1포장당 수량 입력 요망");
		var manual = body();
		manual.put("base_qty_per_order_unit", "950");
		service.link(manual);
		verify(mapper).linkProductLines(argThat(p -> new BigDecimal("950").compareTo((BigDecimal) p.get("per_unit")) == 0));
	}

	@Test
	void unlinkRestoresProductUnits() {
		product("1000", "g", null, null);
		var b = body();
		b.put("ingredient_id", "");
		service.link(b);
		verify(mapper).linkProductLines(argThat(p -> p.get("ingredient_id") == null && "g".equals(p.get("base_unit"))
				&& new BigDecimal("1000").compareTo((BigDecimal) p.get("per_unit")) == 0));
		verify(mapper, never()).accountIngredient(anyMap());
	}

	@Test
	void productsAreGradedAbcByCumulativeAmount() {
		when(mapper.purchasedProducts(anyMap())).thenReturn(List.of(
				Map.of("supplier_product_id", 1L, "total_supply_amount", new BigDecimal("600")),
				Map.of("supplier_product_id", 2L, "total_supply_amount", new BigDecimal("200")),
				Map.of("supplier_product_id", 3L, "total_supply_amount", new BigDecimal("150")),
				Map.of("supplier_product_id", 4L, "total_supply_amount", new BigDecimal("50"))));
		var result = service.products(Map.of("account_id", "A1"));
		var rows = (List<Map<String, Object>>) result.get("products");
		assertThat(rows).extracting(r -> r.get("abc_class")).containsExactly("A", "A", "B", "C");
		assertThat(result.get("abc_counts")).isEqualTo(Map.of("A", 2, "B", 1, "C", 1));
		assertThat((BigDecimal) rows.get(0).get("amount_share_pct")).isEqualByComparingTo("60");
	}

	@Test
	void ingredientPriceSpreadIsFlaggedFromThirtyPercent() {
		when(mapper.ingredientPrices(anyMap())).thenReturn(List.of(
				Map.of("ingredient_id", "ONION", "weighted_unit_cost", new BigDecimal("2.0"), "min_unit_cost", new BigDecimal("1.6"), "max_unit_cost", new BigDecimal("2.2")),
				Map.of("ingredient_id", "RICE", "weighted_unit_cost", new BigDecimal("3.0"), "min_unit_cost", new BigDecimal("2.9"), "max_unit_cost", new BigDecimal("3.1"))));
		var rows = (List<Map<String, Object>>) service.ingredientPrices(Map.of("account_id", "A1")).get("ingredients");
		assertThat(rows).extracting(r -> r.get("price_varies")).containsExactly("Y", "N");
		assertThat((BigDecimal) rows.get(0).get("spread_pct")).isEqualByComparingTo("30");
	}

	@Test
	void priceLinesShowDeviationFromWeightedAverage() {
		when(mapper.ingredientPriceLines(anyMap())).thenReturn(List.of(
				Map.of("base_received_qty", new BigDecimal("1000"), "supply_amount", new BigDecimal("2000"), "unit_cost", new BigDecimal("2")),
				Map.of("base_received_qty", new BigDecimal("1000"), "supply_amount", new BigDecimal("4000"), "unit_cost", new BigDecimal("4"))));
		var result = service.ingredientPriceLines(Map.of("account_id", "A1", "ingredient_id", "ONION"));
		assertThat((BigDecimal) result.get("weighted_unit_cost")).isEqualByComparingTo("3");
		var lines = (List<Map<String, Object>>) result.get("lines");
		assertThat((BigDecimal) lines.get(0).get("deviation_pct")).isEqualByComparingTo("-33.3");
		assertThat(lines).extracting(r -> r.get("price_outlier")).containsExactly("Y", "Y");
	}

	@Test
	void savingStartDateReclassifiesImportedHistory() {
		when(mapper.reclassifyHistory(anyMap())).thenReturn(5);
		when(mapper.inventoryStartDate(anyMap())).thenReturn("2026-12-01");
		var result = service.saveSettings(new HashMap<>(Map.of("account_id", "A1", "inventory_start_date", "2026-12-01", "user_id", "HQ")));
		verify(mapper).saveInventorySetting(argThat(p -> "2026-12-01".equals(p.get("inventory_start_date"))));
		assertThat(result).containsEntry("inventory_start_date", "2026-12-01").containsEntry("reclassified_receipt_count", 5);
		assertThatThrownBy(() -> service.saveSettings(new HashMap<>(Map.of("account_id", "A1", "inventory_start_date", "12/01"))))
				.hasMessageContaining("YYYY-MM-DD");
	}
}
