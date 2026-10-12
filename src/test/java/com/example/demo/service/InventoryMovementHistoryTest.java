package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.InventoryMapper;

/** 재고 변동 이력 조회 조건: 구분 목록·검색어·페이지 검증 */
class InventoryMovementHistoryTest {
    final InventoryMapper mapper=mock(InventoryMapper.class);
    final InventoryService service=new InventoryService(mapper,mock(StockAvailabilityService.class));

    Map<String,Object> params(Object... kv) {
        var p=new HashMap<String,Object>();p.put("account_id","A");
        for(int i=0;i<kv.length;i+=2) p.put(kv[i].toString(),kv[i+1]);
        return p;
    }

    @Test void movementTypesKeepOnlyAllowedValues() {
        service.movements(params("movement_type","in, out,DROP TABLE,in","keyword","  양파 "));
        verify(mapper).movements(argThat(p->List.of("IN","OUT").equals(p.get("movement_types")) && "양파".equals(p.get("keyword"))
            && Integer.valueOf(50).equals(p.get("limit")) && Integer.valueOf(0).equals(p.get("offset"))));
    }
    @Test void unknownMovementTypeOnlyIsRejected() {
        assertThatThrownBy(()->service.movements(params("movement_type","DROP"))).hasMessageContaining("변동 구분");
        verifyNoInteractions(mapper);
    }
    @Test void summaryUsesSameFilterWithoutPaging() {
        service.movementSummary(params("movement_type","ADJUST,DISCARD","date_from","2026-10-01","date_to","2026-10-12"));
        verify(mapper).movementSummary(argThat(p->List.of("ADJUST","DISCARD").equals(p.get("movement_types")) && !p.containsKey("limit")));
    }
    @Test void invalidDateOrRangeIsRejected() {
        assertThatThrownBy(()->service.movementSummary(params("date_from","2026/10/01"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.movements(params("limit","500"))).hasMessageContaining("이력 조회 범위");
    }
}
