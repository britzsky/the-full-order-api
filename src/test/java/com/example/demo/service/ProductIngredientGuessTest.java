package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

/** 상품 검색 발주: 담을 상품의 재고 식재료 추정 순서(지난 발주 → 상품 지정 → 다른 거래처 → 이름)와 단위 환산 가능 조건 */
class ProductIngredientGuessTest {
    private static final List<ProductIngredientGuess.Ingredient> INGREDIENTS = ProductIngredientGuess.ingredients(List.of(
            ing("ONION", "양파", "g"), ing("RED_ONION", "적양파", "g"), ing("PORK", "돼지고기", "g"),
            ing("EGG", "계란", "EA"), ing("MILK", "우유", "ml"), ing("GREEN", "대파", "g"), ing("LEEK", "쪽파", "g")));

    private static Map<String, Object> ing(String id, String name, String unit) {
        return new HashMap<>(Map.of("ingredient_id", id, "ingredient_name", name, "base_unit", unit));
    }

    private static Map<String, Object> offer(long productId, String name, String qty, String unit) {
        return new HashMap<>(Map.of("supplier_product_id", productId, "product_name", name, "product_base_qty", qty, "product_base_unit", unit));
    }

    private static Map<String, Object> fact(long productId, String ingredient, String scope, int accounts, String lastAt) {
        Map<String, Object> row = new HashMap<>(Map.of("supplier_product_id", productId, "ingredient_id", ingredient, "scope", scope,
                "accounts", accounts, "hits", accounts));
        if (lastAt != null) row.put("last_at", lastAt);
        return row;
    }

    @SafeVarargs
    private static Map<String, Object> guess(Map<String, Object> offer, Map<String, Object>... facts) {
        ProductIngredientGuess.apply(List.of(offer), INGREDIENTS, List.of(facts));
        return offer;
    }

    @Test void ownHistoryWinsAndLatestOrderIsUsed() {
        var row = guess(offer(1, "깐양파 1kg", "1000", "g"),
                fact(1, "RED_ONION", "OWN", 1, "2026-10-01 10:00:00"), fact(1, "ONION", "OWN", 1, "2026-10-20 10:00:00"),
                fact(1, "PORK", "MASTER", 0, null));
        assertThat(row).containsEntry("suggested_ingredient_id", "ONION").containsEntry("suggested_reason", "HISTORY")
                .containsEntry("suggested_ingredient_name", "양파");
    }

    @Test void masterThenOtherAccountsThenName() {
        assertThat(guess(offer(2, "국내산 채소 1kg", "1000", "g"), fact(2, "GREEN", "MASTER", 0, null), fact(2, "ONION", "OTHERS", 3, null)))
                .containsEntry("suggested_ingredient_id", "GREEN").containsEntry("suggested_reason", "MASTER");
        assertThat(guess(offer(3, "국내산 채소 1kg", "1000", "g"), fact(3, "ONION", "OTHERS", 1, null), fact(3, "GREEN", "OTHERS", 4, null)))
                .containsEntry("suggested_ingredient_id", "GREEN").containsEntry("suggested_reason", "OTHER_ACCOUNTS");
        assertThat(guess(offer(4, "돈육 앞다리 2kg", "2000", "g")))
                .containsEntry("suggested_ingredient_id", "PORK").containsEntry("suggested_reason", "NAME");
    }

    @Test void nameMatchPrefersLongerNameAndSkipsTies() {
        assertThat(guess(offer(5, "적양파 1kg", "1", "kg"))).as("양파·적양파 모두 포함 → 더 긴 적양파").containsEntry("suggested_ingredient_id", "RED_ONION");
        assertThat(guess(offer(6, "대파 쪽파 혼합 1kg", "1000", "g"))).as("같은 길이 두 식재료 → 모른다고 본다").doesNotContainKey("suggested_ingredient_id");
        assertThat(guess(offer(7, "냉동 만두 1kg", "1000", "g"))).doesNotContainKey("suggested_ingredient_id");
    }

    @Test void skipsIngredientsTheProductUnitCannotConvertTo() {
        // 계란은 EA 식재료 — 무게만 아는 상품은 이력이 있어도 환산할 수 없어 다음 근거(이름)로 넘어간다
        assertThat(guess(offer(8, "계란 10kg", "10000", "g"), fact(8, "EGG", "OWN", 1, "2026-10-20 10:00:00")))
                .doesNotContainKey("suggested_ingredient_id");
        var both = offer(9, "계란 30구", "30", "EA");
        assertThat(guess(both, fact(9, "EGG", "OWN", 1, "2026-10-20 10:00:00"))).containsEntry("suggested_ingredient_id", "EGG");
        var alt = offer(10, "흰우유 200ml", "200", "g");
        alt.put("alt_base_qty", "200"); alt.put("alt_base_unit", "ml");
        assertThat(guess(alt)).as("두 번째 규격(ml)으로 환산 가능").containsEntry("suggested_ingredient_id", "MILK");
    }
}
