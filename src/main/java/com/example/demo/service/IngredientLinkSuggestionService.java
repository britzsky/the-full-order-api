package com.example.demo.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 거래처 식재료에 연결할 공급상품 추천. 근거: 다른 거래처가 같은 식재료에 연결해 쓰는 상품, 이 거래처의 발주 이력,
 * 상품명 유사도(동의어 포함). 식재료 기준단위로 환산할 수 없는 상품과 이미 이 식재료에 연결된 상품은 제외한다.
 * 추천만 하며 저장은 사용자가 고른 상품으로 기존 연결 API(IngredientLinkService.change)를 쓴다(이름만으로 자동 연결 금지 결정 유지).
 * 단건(연결 창)과 미연결 식재료 일괄(일괄 연결 화면)이 같은 점수 규칙을 쓰고, 상품 목록·근거는 요청당 한 번만 조회한다.
 */
@Service
public class IngredientLinkSuggestionService {
    static final int LIMIT = 5;
    static final int BULK_LIMIT = 3;
    private final JdbcTemplate jdbc;
    private final SupplierIntegrationService suppliers;

    public IngredientLinkSuggestionService(JdbcTemplate jdbc, SupplierIntegrationService suppliers) {
        this.jdbc = jdbc; this.suppliers = suppliers;
    }

    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }

    /** 상품마다 한 번만 계산해 두는 값: 이름 비교용 정규화, 규격 후보, 기준단위별 규격 캐시. */
    private static final class PreparedOffer {
        final Map<String, Object> offer;
        final ProductNameMatch.Product name;
        final List<QuantityUnits.Pack> packs;
        final Map<String, Optional<QuantityUnits.Pack>> packByUnit = new HashMap<>();
        PreparedOffer(Map<String, Object> offer) {
            this.offer = offer;
            this.name = ProductNameMatch.product(text(offer.get("product_name")));
            this.packs = packs(offer);
        }
        QuantityUnits.Pack pack(String unit) {
            return packByUnit.computeIfAbsent(QuantityUnits.unit(unit), key -> Optional.ofNullable(QuantityUnits.packFor(packs, key))).orElse(null);
        }
    }

    /** 요청 하나에서 함께 쓰는 데이터: 거래처 사업장 상품(미리 계산), 다른 거래처 연결 수, 이 거래처 발주 수(식재료|상품 키). */
    private record Context(List<PreparedOffer> offers, Map<String, Integer> otherAccounts, Map<String, Integer> ordered) {}

    private static String date(Map<String, Object> input) {
        String date = text(input.get("delivery_date"));
        if (date.isEmpty()) return LocalDate.now().toString();
        LocalDate.parse(date);
        return date;
    }

    private Context load(String account, String date, String ingredientId) {
        String filter = ingredientId == null ? "" : " AND mapped_ingredient_id=?";
        Object[] args = ingredientId == null ? new Object[] { account } : new Object[] { account, ingredientId };
        Map<String, Integer> others = new HashMap<>();
        for (var row : jdbc.queryForList("SELECT mapped_ingredient_id,supplier_product_id,COUNT(DISTINCT account_id) n FROM the_full_order.tb_account_ingredient_product WHERE active_yn='Y' AND mapped_ingredient_id IS NOT NULL AND account_id<>?" + filter + " GROUP BY mapped_ingredient_id,supplier_product_id", args))
            others.put(text(row.get("mapped_ingredient_id")) + "|" + text(row.get("supplier_product_id")), Integer.parseInt(text(row.get("n"))));
        Map<String, Integer> ordered = new HashMap<>();
        for (var row : jdbc.queryForList("""
                SELECT COALESCE(NULLIF(oi.source_ingredient_id,''),ap.mapped_ingredient_id) ingredient_id,ap.supplier_product_id,COUNT(*) n
                  FROM the_full_order.tb_account_purchase_order_item oi
                  JOIN the_full_order.tb_account_purchase_order po ON po.purchase_order_id=oi.purchase_order_id
                  JOIN the_full_order.tb_account_ingredient_product ap ON ap.account_ingredient_product_id=oi.account_ingredient_product_id
                 WHERE po.account_id=? AND po.status NOT IN ('FAILED','CANCELED') AND oi.item_status NOT IN ('FAILED','CANCELED')
                 GROUP BY COALESCE(NULLIF(oi.source_ingredient_id,''),ap.mapped_ingredient_id),ap.supplier_product_id""", account))
            ordered.put(text(row.get("ingredient_id")) + "|" + text(row.get("supplier_product_id")), Integer.parseInt(text(row.get("n"))));
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("account_id", account);
        query.put("delivery_date", date);
        List<PreparedOffer> prepared = new ArrayList<>();
        for (Map<String, Object> offer : suppliers.offers(query)) prepared.add(new PreparedOffer(offer));
        return new Context(prepared, others, ordered);
    }

    /** 연결 창: 한 식재료의 추천(최대 5개) */
    public Map<String, Object> suggestions(Map<String, Object> input) {
        String account = text(input.get("account_id")), ingredientId = text(input.get("ingredient_id"));
        if (account.isEmpty() || ingredientId.isEmpty()) throw new IllegalArgumentException("account_id와 ingredient_id가 필요합니다.");
        String date = date(input);
        var ingredients = jdbc.queryForList("SELECT base_unit,COALESCE(NULLIF(ingredient_name_std,''),ingredient_name_raw,ingredient_id) name FROM the_full_order.tb_account_ingredient_master WHERE account_id=? AND ingredient_id=?", account, ingredientId);
        if (ingredients.isEmpty()) throw new IllegalArgumentException("거래처 식재료를 찾을 수 없습니다.");
        String unit = text(ingredients.get(0).get("base_unit")), name = text(ingredients.get(0).get("name"));
        Context context = load(account, date, ingredientId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ingredient_id", ingredientId);
        result.put("ingredient_name", name);
        result.put("base_unit", unit);
        result.put("delivery_date", date);
        result.put("suggestions", rank(context, ingredientId, name, unit, LIMIT));
        return result;
    }

    /** 일괄 연결 화면: 메뉴에 쓰이지만 사용 중 연결 상품이 없는 식재료마다 추천(최대 3개). 추천이 없으면 빈 목록(직접 검색 필요). */
    public Map<String, Object> unlinked(Map<String, Object> input) {
        String account = text(input.get("account_id"));
        if (account.isEmpty()) throw new IllegalArgumentException("account_id가 필요합니다.");
        String date = date(input);
        var ingredients = jdbc.queryForList("""
                SELECT ai.ingredient_id,COALESCE(NULLIF(ai.ingredient_name_std,''),ai.ingredient_name_raw,ai.ingredient_id) name,ai.base_unit,
                       COALESCE(ai.category_name,'') category_name,u.menu_usage_count
                  FROM (SELECT rd.ingredient_id,COUNT(DISTINCT rd.menu_id) menu_usage_count
                          FROM the_full_order.tb_account_recipe_detail rd
                          JOIN the_full_order.tb_account_menu_master m ON m.account_id=rd.account_id AND m.menu_id=rd.menu_id AND m.del_yn='N'
                         WHERE rd.account_id=? GROUP BY rd.ingredient_id) u
                  JOIN the_full_order.tb_account_ingredient_master ai ON ai.account_id=? AND ai.ingredient_id=u.ingredient_id
                 WHERE NOT EXISTS (SELECT 1 FROM the_full_order.tb_account_ingredient_product a
                                     JOIN the_full_order.tb_supplier_ingredient_product p ON p.supplier_product_id=a.supplier_product_id AND p.active_yn='Y'
                                    WHERE a.account_id=ai.account_id AND a.mapped_ingredient_id=ai.ingredient_id AND a.active_yn='Y')
                 ORDER BY u.menu_usage_count DESC,name""", account, account);
        List<Map<String, Object>> rows = new ArrayList<>();
        Context context = ingredients.isEmpty() ? null : load(account, date, null);
        for (var ingredient : ingredients) {
            String id = text(ingredient.get("ingredient_id"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ingredient_id", id);
            row.put("ingredient_name", ingredient.get("name"));
            row.put("base_unit", ingredient.get("base_unit"));
            row.put("category_name", ingredient.get("category_name"));
            row.put("menu_usage_count", ingredient.get("menu_usage_count"));
            row.put("suggestions", rank(context, id, text(ingredient.get("name")), text(ingredient.get("base_unit")), BULK_LIMIT));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("delivery_date", date);
        result.put("ingredients", rows);
        return result;
    }

    private List<Map<String, Object>> rank(Context context, String ingredientId, String name, String unit, int limit) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ProductNameMatch.Query nameQuery = ProductNameMatch.query(name);
        for (PreparedOffer prepared : context.offers()) {
            Map<String, Object> offer = prepared.offer;
            String product = text(offer.get("supplier_product_id"));
            if (product.isEmpty() || ingredientId.equals(text(offer.get("linked_ingredient_id"))) || !seen.add(text(offer.get("supplier_offer_id"))))
                continue;
            QuantityUnits.Pack pack = prepared.pack(unit);
            if (pack == null) continue;   // 기준단위로 환산 불가 → 연결할 수 없음
            int score = 0;
            List<String> reasons = new ArrayList<>();
            int others = context.otherAccounts().getOrDefault(ingredientId + "|" + product, 0);
            if (others > 0) { score += Math.min(60, 40 + 5 * (others - 1)); reasons.add("다른 거래처 " + others + "곳에서 이 식재료로 사용"); }
            int orders = context.ordered().getOrDefault(ingredientId + "|" + product, 0);
            if (orders > 0) { score += 30; reasons.add("이 거래처 발주 이력 " + orders + "건"); }
            var match = ProductNameMatch.score(nameQuery, prepared.name);
            if (match.value() > 0) { score += match.value(); reasons.add(match.reason()); }
            if (score <= 0) continue;   // 사용·이름 근거가 없는 상품은 추천하지 않는다
            String warning = "";
            if (!text(offer.get("linked_ingredient_id")).isEmpty()) {
                score -= 10;
                warning = "이 거래처의 '" + (text(offer.get("linked_ingredient_name")).isEmpty() ? text(offer.get("linked_ingredient_id")) : text(offer.get("linked_ingredient_name")))
                    + "'에 연결된 상품입니다. 연결하면 그 식재료에서 빠집니다.";
            }
            if ("N".equals(text(offer.get("orderable_yn"))) || !"ACTIVE".equals(text(offer.get("product_status")))) {
                score -= 15; reasons.add("현재 발주 불가 상태");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("supplier_offer_id", offer.get("supplier_offer_id"));
            row.put("supplier_product_id", offer.get("supplier_product_id"));
            row.put("supplier_code", offer.get("supplier_code"));
            row.put("supplier_name", offer.get("supplier_name"));
            row.put("product_name", offer.get("product_name"));
            row.put("supplier_item_code", offer.get("supplier_item_code"));
            row.put("base_qty", pack.quantity());
            row.put("base_unit", pack.baseUnit());
            row.put("purchase_price", offer.get("purchase_price"));
            row.put("score", score);
            row.put("reasons", reasons);
            row.put("warning", warning);
            candidates.add(row);
        }
        candidates.sort(Comparator.comparing((Map<String, Object> r) -> -(Integer) r.get("score"))
            .thenComparing(IngredientLinkSuggestionService::unitPrice));
        return candidates.size() > limit ? new ArrayList<>(candidates.subList(0, limit)) : candidates;
    }

    private static BigDecimal unitPrice(Map<String, Object> row) {
        try {
            BigDecimal price = new BigDecimal(text(row.get("purchase_price"))), qty = new BigDecimal(text(row.get("base_qty")));
            return price.signum() > 0 && qty.signum() > 0 ? price.divide(qty, 6, java.math.RoundingMode.HALF_UP) : new BigDecimal("999999999");
        } catch (RuntimeException e) { return new BigDecimal("999999999"); }
    }

    /** 상품 규격 후보(대표·두 번째). 연결 시 IngredientLinkService와 같은 규칙으로 기준단위에 맞는 것을 고른다. */
    static List<QuantityUnits.Pack> packs(Map<String, Object> offer) {
        List<QuantityUnits.Pack> packs = new ArrayList<>();
        for (String[] keys : new String[][] { { "product_base_qty", "product_base_unit" }, { "alt_base_qty", "alt_base_unit" } }) {
            String qty = text(offer.get(keys[0]));
            if (!qty.isEmpty()) {
                try { packs.add(new QuantityUnits.Pack(new BigDecimal(qty), text(offer.get(keys[1])))); }
                catch (NumberFormatException ignored) { /* 규격 없음 */ }
            }
        }
        return packs;
    }
}
