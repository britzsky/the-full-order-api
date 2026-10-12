package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.DirectPurchaseMapper;
import com.example.demo.mapper.IngredientLinkMapper;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;

/**
 * 마트·쿠팡 등에서 직접 산 식재료를 재고로 기록한다. 웰스토리/아워홈 상품에 억지로 연결하지 않고
 * "직접구매" 공급사의 거래처 자체 상품을 처음 구매할 때 자동으로 만든다(별도 상품 등록 화면 없음).
 * 입고는 공급사 입고와 같은 이동평균·LOT 규칙을 쓰므로 합산 재고·입고일순 소진·최근 입고 단가 원가에 그대로 반영된다.
 */
@Service
public class DirectPurchaseService {
    private final DirectPurchaseMapper direct;
    private final IngredientLinkMapper links;
    private final OrderWorkflowMapper orders;
    private final SupplierIntegrationMapper stock;

    public DirectPurchaseService(DirectPurchaseMapper direct, IngredientLinkMapper links, OrderWorkflowMapper orders,
            SupplierIntegrationMapper stock) {
        this.direct = direct; this.links = links; this.orders = orders; this.stock = stock;
    }

    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }

    @Transactional
    public Map<String, Object> record(Map<String, Object> body) {
        for (String key : List.of("account_id", "ingredient_id", "purchase_date", "quantity", "unit", "total_amount", "request_key"))
            if (text(body.get(key)).isEmpty()) throw new IllegalArgumentException(key + " is required.");
        String requestKey = text(body.get("request_key"));
        if (!requestKey.matches("[A-Za-z0-9-]{8,60}")) throw new IllegalArgumentException("요청키 형식이 올바르지 않습니다.");
        LocalDate date;
        try { date = LocalDate.parse(text(body.get("purchase_date"))); }
        catch (Exception e) { throw new IllegalArgumentException("구매일을 확인해주세요."); }
        if (date.isAfter(LocalDate.now())) throw new IllegalArgumentException("구매일은 오늘 이후일 수 없습니다.");
        if (!text(body.get("expiration_date")).isEmpty()) {
            try { LocalDate.parse(text(body.get("expiration_date"))); }
            catch (Exception e) { throw new IllegalArgumentException("소비기한을 확인해주세요."); }
        }
        BigDecimal qty, amount;
        try { qty = new BigDecimal(text(body.get("quantity"))); amount = new BigDecimal(text(body.get("total_amount"))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("수량과 결제 금액은 숫자로 입력해주세요."); }
        if (qty.signum() <= 0 || qty.stripTrailingZeros().scale() > 3) throw new IllegalArgumentException("수량은 0보다 크고 소수점 3자리 이하여야 합니다.");
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다.");

        var params = new HashMap<String, Object>();
        params.put("account_id", text(body.get("account_id")));
        params.put("ingredient_id", text(body.get("ingredient_id")));
        params.put("request_key", requestKey);
        params.put("user_id", body.get("user_id"));
        // 거래처 식재료 행을 잠가 같은 식재료의 상품 생성·같은 요청 재시도를 직렬화한다.
        String unit = links.lockAccountIngredientUnit(params);
        if (unit == null) throw new IllegalArgumentException("거래처 식재료를 찾을 수 없습니다.");
        Map<String, Object> prior = direct.recorded(params);
        if (prior != null && prior.get("movement_id") != null) return result(prior, false, true);

        String baseUnit = QuantityUnits.unit(unit);
        BigDecimal baseQty;
        try { baseQty = QuantityUnits.convert(qty, body.get("unit"), baseUnit); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("'" + text(body.get("unit")) + "' 단위는 이 식재료의 기준단위(" + baseUnit + ")로 환산할 수 없습니다."); }

        Map<String, Object> link = resolveProduct(body, params, baseUnit);
        boolean created = Boolean.TRUE.equals(link.remove("created"));
        params.put("account_ingredient_product_id", link.get("account_ingredient_product_id"));
        params.put("supplier_product_id", link.get("supplier_product_id"));
        String location = text(body.get("location_id"));
        if (location.isEmpty()) location = text(link.get("default_location_id"));
        params.put("location_id", location.isEmpty() ? "L999" : location);
        params.put("base_unit", baseUnit);
        orders.ensureBalance(params);
        Map<String, Object> before = stock.inventoryBalanceForUpdate(params);
        if (!QuantityUnits.unit(before.get("base_unit")).equals(baseUnit))
            throw new IllegalArgumentException("이 상품의 재고가 " + before.get("base_unit") + " 단위로 남아 있어 " + baseUnit + "로 기록할 수 없습니다.");
        params.put("base_received_qty", baseQty);
        params.put("receipt_amount", amount);
        params.put("receipt_base_unit_cost", amount.divide(baseQty, 6, RoundingMode.HALF_UP));
        params.put("receipt_date", date.toString());
        params.put("expiration_date", text(body.get("expiration_date")).isEmpty() ? null : text(body.get("expiration_date")));
        params.put("goods_receipt_item_id", null);
        params.put("lot_no", null);
        params.put("origin_name_snapshot", text(body.get("origin")).isEmpty() ? "원산지 미확인 (직접구매)" : text(body.get("origin")));
        params.put("quantity_before", before.get("current_base_qty"));
        params.put("average_unit_cost_before", before.get("average_unit_cost"));
        stock.upsertInventoryAtAverageCost(params);
        Map<String, Object> after = stock.inventoryBalanceForUpdate(params);
        params.put("quantity_after", after.get("current_base_qty"));
        params.put("average_unit_cost_after", after.get("average_unit_cost"));
        params.put("movement_id", UUID.randomUUID().toString());
        stock.insertInventoryLot(params);
        direct.insertMovement(params);

        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("movement_id", params.get("movement_id"));
        saved.put("account_ingredient_product_id", params.get("account_ingredient_product_id"));
        saved.put("supplier_product_id", params.get("supplier_product_id"));
        saved.put("product_name", link.get("product_name"));
        saved.put("quantity_delta", baseQty);
        saved.put("amount_delta", amount);
        saved.put("unit_cost_snapshot", params.get("receipt_base_unit_cost"));
        saved.put("base_unit", baseUnit);
        Map<String, Object> response = result(saved, created, false);
        response.put("preferred", "Y".equals(link.get("preferred_yn")));
        return response;
    }

    /**
     * 사용할 직접구매 상품: 지정한 상품 → 같은 이름의 기존 직접구매 상품 → 새로 만들기.
     * 새 상품은 이 식재료에 우선 상품이 없을 때만 우선 상품이 된다(공급사 발주 상품을 밀어내지 않음).
     */
    private Map<String, Object> resolveProduct(Map<String, Object> body, Map<String, Object> params, String baseUnit) {
        List<Map<String, Object>> existing = direct.directLinks(params);
        String requested = text(body.get("account_ingredient_product_id"));
        if (!requested.isEmpty()) {
            return new HashMap<>(existing.stream()
                .filter(l -> requested.equals(text(l.get("account_ingredient_product_id"))) && "Y".equals(text(l.get("active_yn"))))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("이 식재료의 직접구매 상품을 찾을 수 없습니다. 다시 조회해주세요.")));
        }
        String name = text(body.get("product_name")).replaceAll("\\s+", " ");
        if (name.isEmpty()) name = text(body.get("ingredient_name")).isEmpty() ? "직접구매 상품" : text(body.get("ingredient_name"));
        if (name.length() > 255) throw new IllegalArgumentException("상품명은 255자 이하로 입력해주세요.");
        String productName = name;
        Optional<Map<String, Object>> same = existing.stream()
            .filter(l -> "Y".equals(text(l.get("active_yn"))) && productName.equalsIgnoreCase(text(l.get("product_name")))).findFirst();
        if (same.isPresent()) return new HashMap<>(same.get());

        direct.ensureDirectSupplier();
        var product = new HashMap<String, Object>();
        product.put("supplier_id", direct.directSupplierId());
        product.put("supplier_item_code", "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT));
        product.put("product_name", productName);
        product.put("base_unit", baseUnit);
        product.put("user_id", params.get("user_id"));
        direct.insertProduct(product);
        var link = new HashMap<String, Object>(params);
        link.put("supplier_product_id", product.get("supplier_product_id"));
        link.put("base_unit", baseUnit);
        link.put("preferred_yn", direct.preferredCount(params) == 0 ? "Y" : "N");
        direct.insertLink(link);
        var created = new HashMap<String, Object>();
        created.put("account_ingredient_product_id", link.get("account_ingredient_product_id"));
        created.put("supplier_product_id", product.get("supplier_product_id"));
        created.put("product_name", productName);
        created.put("default_location_id", "L999");
        created.put("preferred_yn", link.get("preferred_yn"));
        created.put("created", true);
        return created;
    }

    private static Map<String, Object> result(Map<String, Object> row, boolean created, boolean replayed) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", replayed ? "이미 기록된 직접구매입니다." : "직접구매를 재고에 반영했습니다.");
        response.put("replayed", replayed);
        response.put("product_created", created);
        for (String key : List.of("movement_id", "account_ingredient_product_id", "supplier_product_id", "product_name", "base_unit"))
            response.put(key, row.get(key));
        response.put("base_qty", row.get("quantity_delta"));
        response.put("total_amount", row.get("amount_delta"));
        response.put("unit_cost", row.get("unit_cost_snapshot"));
        return response;
    }
}
