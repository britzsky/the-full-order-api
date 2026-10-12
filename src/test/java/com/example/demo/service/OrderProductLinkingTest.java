package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

class OrderProductLinkingTest {
    private final OrderWorkflowMapper mapper = mock(OrderWorkflowMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final OrderProductLinking linking = new OrderProductLinking(mapper, json);

    private Map<String,Object> pending() {
        var row = new HashMap<String,Object>(Map.of("account_id","A", "source_ingredient_id","I",
            "supplier_product_id",20L, "account_ingredient_product_id",2L, "ingredient_link_status","PENDING",
            "base_qty_per_order_unit",1000, "base_unit","g", "purchase_order_id","O", "client_ord_item","1"));
        row.put("link_context_snapshot", OrderWorkflowService.linkContextFingerprint(List.of()));
        row.put("product_snapshot", "{}");
        when(mapper.lockSourceIngredient(anyMap())).thenReturn(Map.of("base_unit","g"));
        when(mapper.lockAccountProduct(anyMap())).thenReturn(Map.of("account_ingredient_product_id",2L));
        when(mapper.linkReceivedProduct(anyMap())).thenReturn(1);
        when(mapper.setSourcePreferred(anyMap())).thenReturn(1);
        when(mapper.sourceLinks(anyMap())).thenReturn(List.of(Map.of("supplier_product_id",10L,"preferred_yn","Y")));
        return row;
    }

    @Test void acceptedOrderAddsProductAndSwitchesPreferenceWithoutRemovingOldStock() {
        var row = pending();
        linking.apply(row, true);
        assertThat(row).containsEntry("ingredient_link_status","LINKED");
        verify(mapper).linkReceivedProduct(anyMap());
        verify(mapper).clearSourcePreferred(anyMap());
        verify(mapper).setSourcePreferred(argThat(p -> p.get("supplier_product_id").equals(20L)));
        assertThat(linking.snapshot(row).path("automatic_preference").path("previous_product_ids").get(0).asText()).isEqualTo("10");
        verify(mapper,never()).ensureBalance(anyMap());
    }

    @Test void repeatedConfirmationDoesNotSwitchPreferenceAgain() {
        var row = pending();
        linking.apply(row, true); linking.apply(row, true);
        verify(mapper,times(1)).setSourcePreferred(anyMap());
    }

    @Test void anotherIngredientMappingRemainsUntouched() {
        var row = pending();
        when(mapper.lockAccountProduct(anyMap())).thenReturn(Map.of("mapped_ingredient_id","OTHER","active_yn","Y"));
        linking.apply(row,true);
        assertThat(row).containsEntry("ingredient_link_status","CONFLICT");
        verify(mapper,never()).linkReceivedProduct(anyMap());
        verify(mapper,never()).clearSourcePreferred(anyMap());
    }

    @Test void earlierOrderReconciledLaterDoesNotReplaceNewerPreferredProduct() {
        var row = pending(); when(mapper.newerAcceptedSourceOrders(anyMap())).thenReturn(1);
        linking.apply(row,true);
        assertThat(row).containsEntry("ingredient_link_status","LINKED");
        verify(mapper,never()).clearSourcePreferred(anyMap());
    }

    @Test void laterUserChangesPreventAutomaticPreferenceOverride() {
        var row = pending();
        when(mapper.lockAccountProduct(anyMap())).thenReturn(Map.of("mapped_ingredient_id","I","active_yn","Y"));
        when(mapper.linkContext(anyMap())).thenReturn(List.of(Map.of("supplier_product_id",10L,"mod_at","changed")));
        linking.apply(row,true);
        assertThat(row).containsEntry("ingredient_link_status","LINKED");
        verify(mapper,never()).clearSourcePreferred(anyMap());
    }

    @Test void undoRestoresPreviousPreferenceOnceAndKeepsAddedLink() {
        var row = pending(); linking.apply(row,true); clearInvocations(mapper);
        linking.undo(row); linking.undo(row);
        verify(mapper,times(1)).setSourcePreferred(argThat(p -> "10".equals(p.get("supplier_product_id"))));
        verify(mapper,never()).linkReceivedProduct(anyMap());
        assertThat(linking.snapshot(row).path("automatic_preference").path("undone").asBoolean()).isTrue();
    }

    @Test void undoRejectsChangedConnections() {
        var row = pending(); linking.apply(row,true); clearInvocations(mapper);
        when(mapper.linkContext(anyMap())).thenReturn(List.of(Map.of("mod_at","changed")));
        assertThatThrownBy(() -> linking.undo(row)).hasMessageContaining("이후 상품 연결");
        verify(mapper,never()).clearSourcePreferred(anyMap());
    }

    @Test void incompatibleStockUnitsCannotBeRelabeled() {
        var row = pending(); when(mapper.productStockUnits(anyMap())).thenReturn(List.of("EA"));
        linking.apply(row,true);
        assertThat(row).containsEntry("ingredient_link_status","CONFLICT");
        verify(mapper,never()).linkReceivedProduct(anyMap());
    }
}
