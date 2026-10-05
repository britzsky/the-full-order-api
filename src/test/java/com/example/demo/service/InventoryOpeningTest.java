package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import com.example.demo.mapper.InventoryMapper;

/** 기초재고 등록과 금액이 반영되는 재고 조정 */
class InventoryOpeningTest {
    final InventoryMapper mapper=mock(InventoryMapper.class);
    final InventoryService service=new InventoryService(mapper,mock(StockAvailabilityService.class));

    // 계란 재고행: 기준단위 EA, 1 PAC = 30 EA
    void stock(Object qty,Object average,Object amount) {
        var s=new HashMap<String,Object>(Map.of("inventory_balance_id",259L,"account_id","A","account_ingredient_product_id",321L,
            "location_id","L999","base_unit","EA","current_base_qty",qty,"average_unit_cost",average,"inventory_amount",amount));
        when(mapper.inventoryForUpdate(anyMap())).thenReturn(s);
        when(mapper.productPack(anyMap())).thenReturn(Map.of("order_unit","PAC","pack_qty",30,"pack_unit","EA"));
        when(mapper.updateInventoryValue(anyMap())).thenReturn(1);
        when(mapper.updateInventory(anyMap())).thenReturn(1);
    }
    Map<String,Object> body(Object qty,String unit) {
        var b=new HashMap<String,Object>();b.put("inventory_balance_id",259L);b.put("current_qty",qty);b.put("current_unit",unit);b.put("user_id","T");return b;
    }
    static boolean eq(Object v,String expected) { return new BigDecimal(v.toString()).compareTo(new BigDecimal(expected))==0; }

    @Test void openingSetsAverageCostAmountAndOpeningHistory() {
        stock(90,0,0);var b=body(3,"PAC");b.put("unit_cost","196.33");
        service.opening(b);
        verify(mapper).updateInventoryValue(argThat(p->eq(p.get("current_base_qty"),"90") && eq(p.get("average_unit_cost"),"196.33") && eq(p.get("inventory_amount"),"17669.70")));
        verify(mapper).insertValuedMovement(argThat(p->"OPENING".equals(p.get("movement_type")) && "OPENING".equals(p.get("reference_type"))
            && "259".equals(p.get("reference_id")) && eq(p.get("amount_delta"),"17669.70") && eq(p.get("quantity_delta"),"0")));
    }
    @Test void openingIsBlockedAfterReceiptOrUsageHistory() {
        stock(90,0,0);when(mapper.hasNonAdjustMovement(anyMap())).thenReturn(1);
        var b=body(90,"EA");b.put("unit_cost",200);
        assertThatThrownBy(()->service.opening(b)).hasMessageContaining("기초재고를 등록할 수 없습니다");
        verify(mapper,never()).updateInventoryValue(anyMap());
    }
    @Test void openingRequiresPositiveUnitCostAndRejectsDuplicates() {
        stock(90,0,0);var zero=body(90,"EA");zero.put("unit_cost",0);
        assertThatThrownBy(()->service.opening(zero)).hasMessageContaining("단가");
        var b=body(90,"EA");b.put("unit_cost",200);
        doThrow(new DuplicateKeyException("dup")).when(mapper).insertValuedMovement(anyMap());
        assertThatThrownBy(()->service.opening(b)).hasMessageContaining("이미 등록");
    }
    @Test void adjustmentValuesDifferenceAtAverageCost() {
        stock(90,200,18000);service.update(body(88,"EA"));
        verify(mapper).updateInventoryValue(argThat(p->eq(p.get("inventory_amount"),"17600") && eq(p.get("average_unit_cost"),"200")));
        verify(mapper).insertValuedMovement(argThat(p->"ADJUST".equals(p.get("movement_type")) && eq(p.get("quantity_delta"),"-2") && eq(p.get("amount_delta"),"-400")));
    }
    @Test void adjustmentToZeroClearsAmount() {
        stock(90,200,18000);service.update(body(0,"EA"));
        verify(mapper).updateInventoryValue(argThat(p->eq(p.get("inventory_amount"),"0")));
    }
    @Test void increaseWithoutAverageCostAsksForOpening() {
        stock(90,0,0);
        assertThatThrownBy(()->service.update(body(120,"EA"))).hasMessageContaining("기초재고 등록");
        verify(mapper,never()).insertValuedMovement(anyMap());
    }
    @Test void unchangedQuantityOnlyUpdatesFieldsWithoutHistory() {
        stock(90,200,18000);service.update(body(3,"PAC"));
        verify(mapper).updateInventory(anyMap());
        verify(mapper,never()).insertValuedMovement(anyMap());
    }
}
