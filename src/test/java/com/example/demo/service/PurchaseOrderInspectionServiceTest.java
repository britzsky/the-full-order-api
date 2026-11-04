package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PurchaseOrderInspectionServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchaseOrderInspectionService service=new PurchaseOrderInspectionService(jdbc);
    private void status(String status) {
        when(jdbc.queryForList(anyString(),eq("A"),eq("O"))).thenReturn(List.of(Map.of("status",status)));
        when(jdbc.queryForMap(anyString(),eq("A"),eq("O"))).thenReturn(Map.of("purchase_order_id","O","status",status));
    }
    @Test void inspectRecordsTimeAndUserOnce() {
        status("ORDERED");
        service.inspect("A","O","u1",true);
        verify(jdbc).update(contains("inspected_at=COALESCE(inspected_at,NOW())"),eq("u1"),eq("A"),eq("O"));
    }
    @Test void cancelClearsInspection() {
        status("PARTIAL_RECEIVED");
        service.inspect("A","O","u1",false);
        verify(jdbc).update(contains("inspected_at=NULL"),eq("A"),eq("O"));
    }
    @Test void receivedOrderCannotBeUninspected() {
        status("RECEIVED");
        assertThatThrownBy(()->service.inspect("A","O","u1",false)).hasMessageContaining("검수 취소 불가");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void uncertainOrderCannotBeInspected() {
        status("UNKNOWN");
        assertThatThrownBy(()->service.inspect("A","O","u1",true)).hasMessageContaining("접수 확인");
    }
    @Test void missingOrderIsRejected() {
        when(jdbc.queryForList(anyString(),eq("A"),eq("X"))).thenReturn(List.of());
        assertThatThrownBy(()->service.inspect("A","X","u1",true)).hasMessageContaining("찾을 수 없습니다");
    }
}
