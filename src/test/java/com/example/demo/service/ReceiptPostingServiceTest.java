package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;

class ReceiptPostingServiceTest {
    private final SupplierIntegrationMapper mapper=mock(SupplierIntegrationMapper.class);
    private final OrderWorkflowMapper orders=mock(OrderWorkflowMapper.class);
    private final ReceiptPostingService service=new ReceiptPostingService(mapper,orders);
    private Map<String,Object> row() {
        Map<String,Object> row=new HashMap<>();
        row.put("account_id","A");row.put("purchase_order_id","O");row.put("purchase_order_item_id",1L);
        row.put("supplier_id",1L);row.put("provider_receipt_key","API|O");row.put("provider_line_key","API|O|1");
        row.put("received_qty",4);row.put("supply_amount",4000);
        when(orders.lockOrder(anyMap())).thenReturn(Map.of("status","ORDERED"));
        when(orders.receiptItem(anyMap())).thenReturn(null);
        return row;
    }
    @Test void repeatedReceiptDoesNotIncreaseInventory() {
        var row=row();when(orders.receiptItem(anyMap())).thenReturn(Map.of("received_qty",4,"supply_amount",4000));
        assertThat(service.post(row)).isFalse();verifyNoInteractions(mapper);
    }
    @Test void changedReceiptRequiresReconciliation() {
        var row=row();when(orders.receiptItem(anyMap())).thenReturn(Map.of("received_qty",3,"supply_amount",3000));
        assertThatThrownBy(()->service.post(row)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(mapper);
    }
    @Test void providerReceiptAfterManualReceiptDoesNotDoubleStock() {
        var row=row();when(orders.manualReceiptCount(anyMap())).thenReturn(1);
        assertThatThrownBy(()->service.post(row)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("수동 입고");
        verifyNoInteractions(mapper);
    }
    @Test void receivedAmountCannotExceedRemainingOrderQuantity() {
        var row=row();when(orders.items(anyMap())).thenReturn(List.of(Map.of("purchase_order_item_id",1L,"received_order_qty",8,"order_qty",10)));
        assertThatThrownBy(()->service.post(row)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("잔량");
        verifyNoInteractions(mapper);
    }
    private Map<String,Object> successfulReceipt(String ingredient) {
        var row=row();
        var item=new HashMap<String,Object>(Map.of("purchase_order_item_id",1L,"received_order_qty",0,
            "order_qty",10,"base_qty_per_order_unit",1000,"supplier_product_id",10L,"base_unit","g","order_unit","PAC",
            "product_name","웰스토리 양파 1kg","product_base_qty",1000,"product_base_unit","g"));
        if (ingredient!=null) item.put("ingredient_id",ingredient);
        when(orders.items(anyMap())).thenReturn(List.of(item));
        when(mapper.insertGoodsReceiptItem(anyMap())).thenReturn(1);
        when(mapper.inventoryBalanceForUpdate(anyMap())).thenReturn(
            Map.of("current_base_qty",3000,"average_unit_cost",1),
            Map.of("current_base_qty",7000,"average_unit_cost",1));
        return row;
    }
    @Test void receiptAddsToTheIngredientStockAndRecordsTheProduct() {
        var row=successfulReceipt("ONION");
        assertThat(service.post(row)).isTrue();
        verify(mapper).upsertInventoryAtAverageCost(argThat(p -> "ONION".equals(p.get("ingredient_id"))
            && new java.math.BigDecimal("4000").compareTo((java.math.BigDecimal)p.get("base_received_qty"))==0));
        verify(mapper).insertInventoryLot(argThat(p -> "ONION".equals(p.get("ingredient_id")) && Long.valueOf(10L).equals(p.get("supplier_product_id"))
            && "웰스토리 양파 1kg".equals(p.get("product_name"))));
        verify(mapper).insertCostedInventoryMovement(argThat(p -> "ONION".equals(p.get("ingredient_id"))));
    }
    @Test void productOnlyLineCannotBeReceivedWithoutChoosingAnIngredient() {
        var row=successfulReceipt(null);
        assertThatThrownBy(()->service.post(row)).hasMessageContaining("재고에 넣을 식재료 선택 요망");
        verify(mapper,never()).upsertInventoryAtAverageCost(anyMap());
    }
}
