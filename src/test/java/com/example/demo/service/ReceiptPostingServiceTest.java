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
    private com.fasterxml.jackson.databind.JsonNode manualReceipt() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"account_id\":\"A\",\"purchase_order_id\":\"O\",\"receipt_key\":\"K1\",\"receipt_date\":\"2026-10-29\",\"items\":[{\"client_ord_item\":\"1\",\"received_qty\":1}]}");
    }
    @Test void manualReceiptNeedsInspectionFirst() throws Exception {
        when(orders.lockOrder(anyMap())).thenReturn(new HashMap<>(Map.of("status","ORDERED")));
        assertThatThrownBy(()->service.receive(manualReceipt())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("검수완료");
        verify(orders,never()).items(anyMap());verifyNoInteractions(mapper);
    }
    @Test void inspectedOrderPassesTheInspectionGate() throws Exception {
        when(orders.lockOrder(anyMap())).thenReturn(new HashMap<>(Map.of("status","ORDERED","inspected_at",java.time.LocalDateTime.now())));
        when(orders.items(anyMap())).thenReturn(List.of());
        // 검수 관문은 통과하고 다음 검사(주문에 없는 품목)에서 멈춘다
        assertThatThrownBy(()->service.receive(manualReceipt())).hasMessageContaining("주문에 없는 품목");
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
    /** ASP: 수동 입고는 발주 단가·과세 구분으로 부가세를 추정하고(별도 기준), 입고 머리 합계를 줄 합계로 다시 맞춘다 */
    @Test void manualReceiptEstimatesVatFromTaxTypeAndRefreshesHeaderTotals() throws Exception {
        when(orders.lockOrder(anyMap())).thenReturn(new HashMap<>(Map.of("status","ORDERED","inspected_at",java.time.LocalDateTime.now())));
        var line=new HashMap<String,Object>(Map.of("purchase_order_item_id",1L,"client_ord_item","1","received_order_qty",0,
            "order_qty",10,"base_qty_per_order_unit",1000,"supplier_product_id",10L,"base_unit","g","order_unit","PAC",
            "product_name","양파","unit_price_snapshot",new java.math.BigDecimal("1235")));
        line.put("ingredient_id","ONION");line.put("product_tax_type","Full tax");line.put("supplier_code","WELSTORY");
        when(orders.items(anyMap())).thenReturn(List.of(line));
        when(orders.order(anyMap())).thenReturn(Map.of("status","RECEIVED"));
        when(orders.receiptItem(anyMap())).thenReturn(null);
        when(mapper.insertGoodsReceiptItem(anyMap())).thenReturn(1);
        when(mapper.inventoryBalanceForUpdate(anyMap())).thenReturn(Map.of("current_base_qty",0,"average_unit_cost",0));
        var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"account_id\":\"A\",\"purchase_order_id\":\"O\",\"receipt_key\":\"K1\",\"receipt_date\":\"2026-10-29\",\"items\":[{\"client_ord_item\":\"1\",\"received_qty\":3}]}");
        service.receive(payload);
        verify(mapper).insertGoodsReceiptItem(argThat(p -> "TAXABLE".equals(p.get("tax_type")) && "ORDER_ESTIMATE".equals(p.get("amount_source"))
            && "UNCHECKED".equals(p.get("amount_check_status"))
            && new java.math.BigDecimal("3705").compareTo((java.math.BigDecimal)p.get("supply_amount"))==0
            && new java.math.BigDecimal("370").compareTo((java.math.BigDecimal)p.get("vat_amount"))==0
            && new java.math.BigDecimal("4075").compareTo((java.math.BigDecimal)p.get("total_amount"))==0));
        // 재고 원가는 부가세 제외 공급가
        verify(mapper).upsertInventoryAtAverageCost(argThat(p -> new java.math.BigDecimal("3705").compareTo((java.math.BigDecimal)p.get("receipt_amount"))==0));
        verify(mapper).refreshReceiptTotals(anyMap());
    }
    @Test void taxTypeComesFromOrderSnapshotFirst() {
        var line=new HashMap<String,Object>();
        line.put("product_snapshot","{\"tax_type\":\"No tax\"}");line.put("product_tax_type","Full tax");
        assertThat(ReceiptPostingService.taxType(line)).isEqualTo("TAX_FREE");
        line.put("product_snapshot",null);
        assertThat(ReceiptPostingService.taxType(line)).isEqualTo("TAXABLE");
    }
    @Test void sameReceiptAgainIsIgnoredEvenAfterSupplierAmountReplacedOurs() {
        var row=row();
        when(orders.receiptItem(anyMap())).thenReturn(Map.of("received_qty",4,"supply_amount",4100,"amount_source","SUPPLIER","amount_check_status","PRICE_DIFF"));
        assertThat(service.post(row)).isFalse();verifyNoInteractions(mapper);
    }
    @Test void productOnlyLineCannotBeReceivedWithoutChoosingAnIngredient() {
        var row=successfulReceipt(null);
        assertThatThrownBy(()->service.post(row)).hasMessageContaining("재고에 넣을 식재료 선택 요망");
        verify(mapper,never()).upsertInventoryAtAverageCost(anyMap());
    }
}
