package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.DirectPurchaseMapper;
import com.example.demo.mapper.IngredientLinkMapper;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;

/** 직접구매: 거래처 자체 상품 자동 생성, 결제 금액 ÷ 기준수량 단가 입고, 재시도 중복 방지 */
class DirectPurchaseServiceTest {
    final DirectPurchaseMapper direct=mock(DirectPurchaseMapper.class);
    final IngredientLinkMapper links=mock(IngredientLinkMapper.class);
    final OrderWorkflowMapper orders=mock(OrderWorkflowMapper.class);
    final SupplierIntegrationMapper stock=mock(SupplierIntegrationMapper.class);
    final DirectPurchaseService service=new DirectPurchaseService(direct,links,orders,stock);

    static boolean eq(Object v,String expected) { return v!=null && new BigDecimal(v.toString()).compareTo(new BigDecimal(expected))==0; }

    Map<String,Object> body() {
        var b=new HashMap<String,Object>();
        b.put("account_id","A");b.put("ingredient_id","ONION");b.put("ingredient_name","양파");b.put("product_name","양파 (쿠팡)");
        b.put("purchase_date",LocalDate.now().toString());b.put("quantity","3");b.put("unit","kg");b.put("total_amount","9000");
        b.put("request_key","DIRECT-REQUEST-1");b.put("user_id","T");
        return b;
    }
    void ingredient(String unit) {
        when(links.lockAccountIngredientUnit(anyMap())).thenReturn(unit);
        when(stock.inventoryBalanceForUpdate(anyMap())).thenReturn(
            new HashMap<>(Map.of("base_unit","g","current_base_qty",0,"average_unit_cost",0)),
            new HashMap<>(Map.of("base_unit","g","current_base_qty",3000,"average_unit_cost",3)));
        when(direct.directSupplierId()).thenReturn(9L);
        doAnswer(i->{((Map<String,Object>)i.getArgument(0)).put("supplier_product_id",77L);return 1;}).when(direct).insertProduct(anyMap());
        doAnswer(i->{((Map<String,Object>)i.getArgument(0)).put("account_ingredient_product_id",88L);return 1;}).when(direct).insertLink(anyMap());
    }

    @Test void firstPurchaseCreatesOwnProductAndPostsStockAtPaidUnitCost() {
        ingredient("g");
        var result=service.record(body());
        verify(direct).ensureDirectSupplier();
        verify(direct).insertProduct(argThat(p->"양파 (쿠팡)".equals(p.get("product_name")) && "g".equals(p.get("base_unit"))
            && p.get("supplier_item_code").toString().matches("D[0-9A-F]{20}") && eq(p.get("supplier_id"),"9")));
        verify(direct).insertLink(argThat(p->"ONION".equals(p.get("ingredient_id")) && "Y".equals(p.get("preferred_yn")) && eq(p.get("supplier_product_id"),"77")));
        verify(stock).upsertInventoryAtAverageCost(argThat(p->eq(p.get("base_received_qty"),"3000") && eq(p.get("receipt_amount"),"9000")
            && eq(p.get("receipt_base_unit_cost"),"3") && eq(p.get("account_ingredient_product_id"),"88")));
        verify(stock).insertInventoryLot(argThat(p->p.get("goods_receipt_item_id")==null && LocalDate.now().toString().equals(p.get("receipt_date"))));
        verify(direct).insertMovement(argThat(p->"DIRECT-REQUEST-1".equals(p.get("request_key")) && eq(p.get("quantity_after"),"3000")));
        assertThat(result).containsEntry("product_created",true).containsEntry("replayed",false).containsEntry("preferred",true);
        assertThat(result.get("unit_cost").toString()).startsWith("3");
    }

    @Test void newDirectProductDoesNotReplaceExistingPreferredSupplierProduct() {
        ingredient("g");
        when(direct.preferredCount(anyMap())).thenReturn(1);
        service.record(body());
        verify(direct).insertLink(argThat(p->"N".equals(p.get("preferred_yn"))));
    }

    @Test void samePurchaseNameReusesExistingDirectProduct() {
        ingredient("g");
        when(direct.directLinks(anyMap())).thenReturn(List.of(new HashMap<>(Map.of("account_ingredient_product_id",55L,
            "supplier_product_id",66L,"product_name","양파 (쿠팡)","active_yn","Y","preferred_yn","N","default_location_id","L001"))));
        var result=service.record(body());
        verify(direct,never()).insertProduct(anyMap());
        verify(orders).ensureBalance(argThat(p->eq(p.get("account_ingredient_product_id"),"55") && "L001".equals(p.get("location_id"))));
        assertThat(result).containsEntry("product_created",false);
    }

    @Test void retryWithSameRequestKeyReturnsRecordedPurchaseWithoutPostingAgain() {
        when(links.lockAccountIngredientUnit(anyMap())).thenReturn("g");
        when(direct.recorded(anyMap())).thenReturn(Map.of("movement_id","M1","account_ingredient_product_id",88L,"quantity_delta",3000,"amount_delta",9000,"unit_cost_snapshot",3,"base_unit","g"));
        var result=service.record(body());
        assertThat(result).containsEntry("replayed",true).containsEntry("movement_id","M1");
        verify(stock,never()).upsertInventoryAtAverageCost(anyMap());
        verify(direct,never()).insertMovement(anyMap());
    }

    @Test void rejectsUnconvertibleUnitFutureDateAndOtherIngredientsProduct() {
        ingredient("g");
        var ea=body();ea.put("unit","EA");
        assertThatThrownBy(()->service.record(ea)).hasMessageContaining("환산할 수 없습니다");
        var future=body();future.put("purchase_date",LocalDate.now().plusDays(1).toString());
        assertThatThrownBy(()->service.record(future)).hasMessageContaining("오늘 이후");
        var other=body();other.put("account_ingredient_product_id",999L);
        assertThatThrownBy(()->service.record(other)).hasMessageContaining("직접구매 상품을 찾을 수 없습니다");
        var zero=body();zero.put("total_amount","0");
        assertThatThrownBy(()->service.record(zero)).hasMessageContaining("결제 금액");
        verify(stock,never()).upsertInventoryAtAverageCost(anyMap());
    }

    @Test void unknownAccountIngredientIsRejected() {
        assertThatThrownBy(()->service.record(body())).hasMessageContaining("거래처 식재료를 찾을 수 없습니다");
    }
}
