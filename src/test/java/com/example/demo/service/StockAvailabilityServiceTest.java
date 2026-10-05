package com.example.demo.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class StockAvailabilityServiceTest {
    @Test void inventoryAndMenuUseSameIncomingAndSafetyAdjustedShortageWithUnitConversion() {
        var calculator=mock(ShortageProcurementService.class);
        var shared=new StockAvailabilityService(calculator);
        Map<String,Object> result=new HashMap<>(Map.of("ingredient_id","I","base_unit","g","required_qty",1000,"safe_stock_qty",100,"current_qty",500,"incoming_qty",600,"shortage_qty",0,"issues",List.of()));
        when(calculator.shortages(anyMap())).thenReturn(Map.of("ingredients",List.of(result)));
        var inventory=new HashMap<String,Object>(Map.of("ingredient_id","I","base_unit","kg","current_qty",new BigDecimal("0.2")));
        shared.enrich(List.of(inventory),Map.of("account_id","A"),false);
        assertThat(inventory.get("current_qty")).isEqualTo(new BigDecimal("0.2"));
        assertThat((BigDecimal)inventory.get("ingredient_current_qty")).isEqualByComparingTo("0.5");
        assertThat(inventory.get("stock_status")).isEqualTo("GREEN");
        assertThat((BigDecimal)inventory.get("shortage_qty")).isEqualByComparingTo("0");
        var menu=new HashMap<String,Object>(Map.of("ingredient_id","I","base_unit","g"));
        shared.enrich(List.of(menu),Map.of("account_id","A","menu_id","M","servingQty",1),true);
        assertThat(menu.get("stock_status")).isEqualTo("GREEN");
        assertThat((BigDecimal)menu.get("current_qty")).isEqualByComparingTo("500");
        verify(calculator).shortages(argThat(p->"DRAFT".equals(p.get("source"))&&Boolean.TRUE.equals(p.get("include_sufficient"))));
    }
}
