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
    private Map<String,Object> successfulReceipt() {
        var row=row();
        var item=new HashMap<String,Object>(Map.of("purchase_order_item_id",1L,"received_order_qty",0,
            "order_qty",10,"base_qty_per_order_unit",1000,"account_ingredient_product_id",7L,
            "supplier_product_id",10L,"base_unit","g","order_unit","PAC",
            "source_ingredient_id","ONION","ingredient_link_status","PENDING"));
        when(orders.items(anyMap())).thenReturn(List.of(item));
        item.put("link_context_snapshot",OrderWorkflowService.linkContextFingerprint(List.of()));
        when(orders.lockSourceIngredient(anyMap())).thenReturn(Map.of("base_unit","g"));
        when(mapper.insertGoodsReceiptItem(anyMap())).thenReturn(1);
        when(mapper.inventoryBalanceForUpdate(anyMap())).thenReturn(
            Map.of("current_base_qty",3000,"average_unit_cost",1),
            Map.of("current_base_qty",7000,"average_unit_cost",1));
        return row;
    }
    @Test void receiptUsesSavedProductAndLinksOriginalIngredient() {
        var row=successfulReceipt();row.put("account_ingredient_product_id",999L);
        when(orders.linkReceivedProduct(anyMap())).thenReturn(1);
        assertThat(service.post(row)).isTrue();
        verify(mapper).upsertInventoryAtAverageCost(argThat(p -> Long.valueOf(7).equals(p.get("account_ingredient_product_id"))
            && new java.math.BigDecimal("4000").compareTo((java.math.BigDecimal)p.get("base_received_qty"))==0));
        verify(orders).updateLinkStatus(argThat(p -> "LINKED".equals(p.get("ingredient_link_status"))));
    }
    @Test void laterUserMappingIsPreservedButPurchasedStockStillArrives() {
        var row=successfulReceipt();
        when(orders.sourceLinks(anyMap())).thenReturn(List.of(Map.of("supplier_product_id",20L)));
        assertThat(service.post(row)).isTrue();
        verify(orders,never()).linkReceivedProduct(anyMap());
        verify(orders).updateLinkStatus(argThat(p -> "CONFLICT".equals(p.get("ingredient_link_status"))));
        verify(mapper).upsertInventoryAtAverageCost(anyMap());
    }
    @Test void changedTargetMappingIsNotOverwritten() {
        var row=successfulReceipt();when(orders.linkReceivedProduct(anyMap())).thenReturn(0);
        service.post(row);
        verify(orders).updateLinkStatus(argThat(p -> "CONFLICT".equals(p.get("ingredient_link_status"))));
    }
    @Test void userUnlinkAfterOrderIsNotUndoneByReceipt() {
        var row=successfulReceipt();
        when(orders.linkContext(anyMap())).thenReturn(List.of(Map.of("active_yn","N","mod_at","changed")));
        service.post(row);
        verify(orders,never()).linkReceivedProduct(anyMap());
        verify(orders).updateLinkStatus(argThat(p -> "CONFLICT".equals(p.get("ingredient_link_status"))));
    }
}
