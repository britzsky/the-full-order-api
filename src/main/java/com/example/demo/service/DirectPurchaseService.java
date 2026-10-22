package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.DirectPurchaseMapper;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;

/**
 * 마트·쿠팡 등에서 직접 산 식재료를 재고로 기록한다. 재고는 거래처 식재료 단위라 공급사 상품을 만들지 않고
 * 그 식재료 재고에 바로 더한다(2026-10-22). 산 물건 이름·원산지는 LOT와 이력에 남긴다.
 * 입고는 공급사 입고와 같은 이동평균·LOT 규칙을 쓰므로 합산 재고·입고일순 소진·입고 단가 원가에 그대로 반영된다.
 */
@Service
public class DirectPurchaseService {
    private final DirectPurchaseMapper direct;
    private final OrderWorkflowMapper orders;
    private final SupplierIntegrationMapper stock;

    public DirectPurchaseService(DirectPurchaseMapper direct, OrderWorkflowMapper orders, SupplierIntegrationMapper stock) {
        this.direct = direct; this.orders = orders; this.stock = stock;
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
        String unit = direct.lockAccountIngredientUnit(params);
        if (unit == null) throw new IllegalArgumentException("거래처 식재료를 찾을 수 없습니다.");
        Map<String, Object> prior = direct.recorded(params);
        if (prior != null && prior.get("movement_id") != null) return result(prior, true);

        String baseUnit = QuantityUnits.unit(unit);
        BigDecimal baseQty;
        try { baseQty = QuantityUnits.convert(qty, body.get("unit"), baseUnit); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("'" + text(body.get("unit")) + "' 단위는 이 식재료의 기준단위(" + baseUnit + ")로 환산할 수 없습니다."); }

        String name = text(body.get("product_name")).replaceAll("\\s+", " ");
        if (name.isEmpty()) name = text(body.get("ingredient_name")).isEmpty() ? "직접구매" : text(body.get("ingredient_name")) + " (직접구매)";
        if (name.length() > 255) throw new IllegalArgumentException("상품명은 255자 이하로 입력해주세요.");
        String location = text(body.get("location_id"));
        params.put("location_id", location.isEmpty() ? "L999" : location);
        params.put("base_unit", baseUnit);
        params.put("product_name", name);
        params.put("supplier_product_id", null);
        orders.ensureBalance(params);
        Map<String, Object> before = stock.inventoryBalanceForUpdate(params);
        if (!QuantityUnits.unit(before.get("base_unit")).equals(baseUnit))
            throw new IllegalArgumentException("이 식재료의 재고가 " + before.get("base_unit") + " 단위로 남아 있어 " + baseUnit + "로 기록할 수 없습니다.");
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
        saved.put("ingredient_id", params.get("ingredient_id"));
        saved.put("product_name", name);
        saved.put("quantity_delta", baseQty);
        saved.put("amount_delta", amount);
        saved.put("unit_cost_snapshot", params.get("receipt_base_unit_cost"));
        saved.put("base_unit", baseUnit);
        return result(saved, false);
    }

    private static Map<String, Object> result(Map<String, Object> row, boolean replayed) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", replayed ? "이미 기록된 직접구매입니다." : "직접구매를 재고에 반영했습니다.");
        response.put("replayed", replayed);
        for (String key : List.of("movement_id", "ingredient_id", "product_name", "base_unit"))
            response.put(key, row.get(key));
        response.put("base_qty", row.get("quantity_delta"));
        response.put("total_amount", row.get("amount_delta"));
        response.put("unit_cost", row.get("unit_cost_snapshot"));
        return response;
    }
}
