package com.example.demo.service;

import java.math.BigDecimal;
import java.util.*;

/**
 * 상품 검색으로 담은 상품이 어느 거래처 식재료 재고로 들어갈지 추정한다(2026-10-23 사용자 결정: 고르게 하지 말고 맞혀서 보여주기).
 * 순서: ① 이 거래처가 이 상품으로 발주했던 식재료(가장 최근) ② 상품 마스터에 지정된 식재료 ③ 다른 거래처가 이 상품으로 받은 식재료(곳 수 많은 것)
 * ④ 상품명에 식재료 이름(동의어 포함)이 들어감(25점 이상, 더 긴 이름 우선, 같으면 추정 안 함).
 * 상품 규격을 식재료 기준단위로 바꿀 수 없는 식재료는 후보에서 뺀다(발주 저장 때 환산 실패를 막기 위함).
 * 결과는 suggested_ingredient_id·suggested_ingredient_name·suggested_reason(HISTORY/MASTER/OTHER_ACCOUNTS/NAME). 못 맞히면 넣지 않는다.
 */
public final class ProductIngredientGuess {
    private ProductIngredientGuess() {}

    /** 거래처 식재료(활성): ingredient_id, ingredient_name, base_unit */
    public record Ingredient(String id, String name, String baseUnit, ProductNameMatch.Query query) {}

    public static List<Ingredient> ingredients(List<Map<String, Object>> rows) {
        List<Ingredient> list = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = text(row.get("ingredient_name"));
            list.add(new Ingredient(text(row.get("ingredient_id")), name, text(row.get("base_unit")), ProductNameMatch.query(name)));
        }
        return list;
    }

    /**
     * facts: supplier_product_id, ingredient_id, scope(OWN/OTHERS/MASTER), accounts, hits, last_at — 매퍼 ingredientGuessFacts.
     */
    public static void apply(List<Map<String, Object>> offers, List<Ingredient> ingredients, List<Map<String, Object>> facts) {
        Map<String, Ingredient> byId = new HashMap<>();
        for (Ingredient ingredient : ingredients) byId.put(ingredient.id(), ingredient);
        Map<String, List<Map<String, Object>>> factsByProduct = new HashMap<>();
        for (Map<String, Object> fact : facts)
            factsByProduct.computeIfAbsent(text(fact.get("supplier_product_id")), k -> new ArrayList<>()).add(fact);
        for (Map<String, Object> offer : offers) {
            List<QuantityUnits.Pack> packs = List.of(
                    new QuantityUnits.Pack(decimal(offer.get("product_base_qty")), text(offer.get("product_base_unit"))),
                    new QuantityUnits.Pack(decimal(offer.get("alt_base_qty")), text(offer.get("alt_base_unit"))));
            Map<String, Boolean> fits = new HashMap<>();
            java.util.function.Predicate<Ingredient> fit = i -> fits.computeIfAbsent(i.id(), k -> QuantityUnits.packFor(packs, i.baseUnit()) != null);
            List<Map<String, Object>> known = factsByProduct.getOrDefault(text(offer.get("supplier_product_id")), List.of());
            Ingredient pick = best(known, "OWN", byId, fit, Comparator.comparing(f -> text(f.get("last_at"))));
            String reason = "HISTORY";
            if (pick == null) { pick = best(known, "MASTER", byId, fit, Comparator.comparing(f -> 0)); reason = "MASTER"; }
            if (pick == null) {
                pick = best(known, "OTHERS", byId, fit, Comparator.<Map<String, Object>, BigDecimal>comparing(f -> decimal(f.get("accounts")))
                        .thenComparing(f -> decimal(f.get("hits"))));
                reason = "OTHER_ACCOUNTS";
            }
            if (pick == null) { pick = byName(text(offer.get("product_name")), ingredients, fit); reason = "NAME"; }
            if (pick == null) continue;
            offer.put("suggested_ingredient_id", pick.id());
            offer.put("suggested_ingredient_name", pick.name());
            offer.put("suggested_reason", reason);
        }
    }

    private static Ingredient best(List<Map<String, Object>> facts, String scope, Map<String, Ingredient> byId,
            java.util.function.Predicate<Ingredient> fit, Comparator<Map<String, Object>> order) {
        return facts.stream().filter(f -> scope.equals(f.get("scope")))
                .filter(f -> byId.containsKey(text(f.get("ingredient_id"))) && fit.test(byId.get(text(f.get("ingredient_id")))))
                .max(order).map(f -> byId.get(text(f.get("ingredient_id")))).orElse(null);
    }

    /** 이름 근거 25점 이상 중 가장 높은 점수 → 더 긴 식재료 이름(적양파 > 양파). 그래도 둘 이상이면 모른다고 본다. */
    static Ingredient byName(String productName, List<Ingredient> ingredients, java.util.function.Predicate<Ingredient> fit) {
        ProductNameMatch.Product product = ProductNameMatch.product(productName);
        Ingredient best = null;
        int bestScore = 0, bestLength = 0;
        boolean tie = false;
        for (Ingredient ingredient : ingredients) {
            int score = ProductNameMatch.score(ingredient.query(), product).value();
            if (score < 25) continue;
            int length = ingredient.query().key().length();
            if (score > bestScore || (score == bestScore && length > bestLength)) {
                if (!fit.test(ingredient)) continue;
                best = ingredient; bestScore = score; bestLength = length; tie = false;
            } else if (score == bestScore && length == bestLength && fit.test(ingredient)) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static BigDecimal decimal(Object value) {
        try { return text(value).isBlank() ? BigDecimal.ZERO : new BigDecimal(text(value)); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
