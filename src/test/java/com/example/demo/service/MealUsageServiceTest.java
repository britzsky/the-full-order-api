package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.MealUsageMapper;

class MealUsageServiceTest {
    private final MealUsageMapper mapper=mock(MealUsageMapper.class);
    private final MealUsageService service=new MealUsageService(mapper);
    private final Map<String,Object> input=new HashMap<>(Map.of("account_id","A","meal_service_id",1,"actual_servings",2));
    private final Map<String,Object> meal=new HashMap<>(Map.of("account_id","A","meal_service_id",1,"meal_date","2026-09-28","status","DRAFT","planned_servings",2));
    private final Map<String,Object> balance=new HashMap<>(Map.of("account_id","A","inventory_balance_id",3,"account_ingredient_product_id",4,"location_id","L999","current_base_qty",new BigDecimal("1000"),"base_unit","g","average_unit_cost",new BigDecimal("0.1"),"inventory_amount",new BigDecimal("100"),"product_name_snapshot","구상품"));
    private final List<Map<String,Object>> allocated=new ArrayList<>();
    void setup() {
        when(mapper.lockService(anyMap())).thenAnswer(i->new HashMap<>(meal));
        when(mapper.requirements(anyMap())).thenReturn(List.of(new HashMap<>(Map.of("menu_id","M","ingredient_id","I","qty_per_person",new BigDecimal("0.1"),"base_unit","kg"))));
        when(mapper.balances(anyMap())).thenAnswer(i->List.of(new HashMap<>(balance)));
        when(mapper.lockBalance(anyMap())).thenAnswer(i->new HashMap<>(balance));
        when(mapper.changeBalance(anyMap())).thenAnswer(i->{ Map<String,Object> p=i.getArgument(0); balance.put("current_base_qty",new BigDecimal(balance.get("current_base_qty").toString()).add((BigDecimal)p.get("quantity_delta")));return 1; });
        when(mapper.changeLot(anyMap())).thenReturn(1);
        when(mapper.insertUsage(anyMap())).thenAnswer(i->{allocated.add(new HashMap<>(i.getArgument(0)));return 1;});
        when(mapper.status(anyMap())).thenAnswer(i->{ Map<String,Object> p=i.getArgument(0);meal.put("status",p.get("status"));return 1; });
        when(mapper.activeUsage(anyMap())).thenAnswer(i->new ArrayList<>(allocated));
        when(mapper.reverseUsage(anyMap())).thenAnswer(i->{allocated.clear();return 1;});
    }
    @Test void usesActualServingsConvertsUnitsAndRestoresSameProductOnce() {
        setup();service.complete(input);
        assertThat((BigDecimal)balance.get("current_base_qty")).isEqualByComparingTo("800");
        assertThat(allocated.get(0)).containsEntry("origin_name_snapshot","원산지 미확인 (기존 재고)");
        service.complete(input); verify(mapper,times(1)).insertUsage(anyMap());
        service.cancel(input);
        assertThat((BigDecimal)balance.get("current_base_qty")).isEqualByComparingTo("1000");
        service.cancel(input);verify(mapper,times(1)).reverseUsage(anyMap());
    }
    @Test void trackedLotOriginWinsOverCurrentProductAndRemainsInUsage() {
        setup();when(mapper.lots(anyMap())).thenReturn(List.of(Map.of("inventory_lot_id",5,"remaining_base_qty",1000,"status","AVAILABLE","origin_name_snapshot","국내산 (입고 당시)")));
        service.complete(input);
        assertThat(allocated.get(0)).containsEntry("inventory_lot_id",5).containsEntry("origin_name_snapshot","국내산 (입고 당시)");
    }
    @Test void insufficientStockDoesNotMarkCompleted() {
        setup();balance.put("current_base_qty",BigDecimal.ZERO);
        assertThatThrownBy(()->service.complete(input)).hasMessageContaining("재고가 부족");
        verify(mapper,never()).status(anyMap()); verify(mapper,never()).insertUsage(anyMap());
    }
    @Test void incompatibleUnitsDoNotUseUnrelatedStock() {
        setup();balance.put("base_unit","EA");
        assertThatThrownBy(()->service.complete(input)).hasMessageContaining("재고가 부족");
        verify(mapper,never()).changeBalance(anyMap());
    }
    @Test void quarantinedOrExpiredLotsCannotBeConsumed() {
        setup();when(mapper.lots(anyMap())).thenReturn(List.of(Map.of("inventory_lot_id",5,"remaining_base_qty",1000,"status","QUARANTINED")));
        assertThatThrownBy(()->service.complete(input)).hasMessageContaining("재고가 부족");
        verify(mapper,never()).insertUsage(anyMap());
    }
    @Test void canceledServiceMustBeReopenedBeforePosting() {
        setup();meal.put("status","CANCELED");
        assertThatThrownBy(()->service.complete(input)).hasMessageContaining("취소된 식단");
    }
    @Test void lotBalanceMismatchRequiresReconciliation() {
        setup();when(mapper.lots(anyMap())).thenReturn(List.of(Map.of("remaining_base_qty",2000)));
        assertThatThrownBy(()->service.complete(input)).hasMessageContaining("LOT 수량");
        verify(mapper,never()).changeBalance(anyMap());
    }
}
