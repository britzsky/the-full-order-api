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
    @Test void fifoUsesEarlierReceiptAcrossDifferentSupplierProducts() {
        setup();
        var other = new HashMap<>(balance); other.put("inventory_balance_id",8); other.put("account_ingredient_product_id",9);
        when(mapper.balances(anyMap())).thenReturn(List.of(balance,other));
        when(mapper.lots(anyMap())).thenAnswer(i -> {
            Map<String,Object> p=i.getArgument(0);
            return List.of(Map.of("inventory_lot_id",p.get("account_ingredient_product_id"),"remaining_base_qty",1000,
                "status","AVAILABLE","received_date",p.get("account_ingredient_product_id").equals(9)?"2026-09-01":"2026-09-20"));
        });
        service.complete(input);
        assertThat(allocated).singleElement().satisfies(p -> assertThat(p).containsEntry("account_ingredient_product_id",9).containsEntry("inventory_lot_id",9));
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
    @Test void futureMealCannotBeConfirmed() {
        setup(); meal.put("meal_date",MealUsageService.today().plusDays(1).toString());
        assertThatThrownBy(()->service.complete(input)).hasMessageContaining("아직 지나지 않은 식사");
        verify(mapper,never()).insertUsage(anyMap());
    }
    @Test void testSwitchAllowsFutureMealWithSameDeduction() {
        setup(); service.setAllowUnserved(true);
        String tomorrow=MealUsageService.today().plusDays(1).toString();
        meal.put("meal_date",tomorrow);
        when(mapper.weekMeals(anyMap())).thenReturn(List.of(weekMeal(1,tomorrow,"DRAFT")));
        @SuppressWarnings("unchecked") var meals=(List<Map<String,Object>>)service.week(Map.of("account_id","A","table_id","T")).get("meals");
        assertThat(meals.get(0)).containsEntry("served",false).containsEntry("confirmable",true);
        service.completeWeek(new HashMap<>(Map.of("account_id","A","table_id","T","meals",List.of(Map.of("meal_service_id",1,"actual_servings",3)))));
        assertThat((BigDecimal)balance.get("current_base_qty")).isEqualByComparingTo("700");
    }
    private Map<String,Object> weekMeal(int id,String date,String status) {
        return new HashMap<>(Map.of("meal_service_id",id,"table_id","T","meal_date",date,"meal_slot","중식","status",status,"planned_servings",2,"needs_reconfirm",0));
    }
    @Test void weekMarksOnlyServedDraftMealsConfirmable() {
        String today=MealUsageService.today().toString(), tomorrow=MealUsageService.today().plusDays(1).toString();
        when(mapper.weekMeals(anyMap())).thenReturn(List.of(weekMeal(1,today,"DRAFT"),weekMeal(2,tomorrow,"DRAFT"),weekMeal(3,today,"COMPLETED")));
        @SuppressWarnings("unchecked") var meals=(List<Map<String,Object>>)service.week(Map.of("account_id","A","table_id","T")).get("meals");
        assertThat(meals).extracting(m->m.get("confirmable")).containsExactly(true,false,false);
        assertThat(meals.get(1)).containsEntry("served",false);
    }
    @Test void completeWeekConfirmsEveryServedMealWithActualServings() {
        setup(); String today=MealUsageService.today().toString();
        meal.put("meal_date",today);
        when(mapper.weekMeals(anyMap())).thenReturn(List.of(weekMeal(1,today,"DRAFT")));
        var result=service.completeWeek(new HashMap<>(Map.of("account_id","A","table_id","T","meals",List.of(Map.of("meal_service_id",1,"actual_servings",3)))));
        assertThat(result).containsEntry("completed_count",1);
        assertThat((BigDecimal)balance.get("current_base_qty")).as("0.1kg × 실제 3명 = 300g 차감").isEqualByComparingTo("700");
        assertThat(meal).containsEntry("status","COMPLETED");
    }
    @Test void completeWeekRejectsMealsThatAreNotConfirmableAndNamesShortMeal() {
        setup(); String today=MealUsageService.today().toString();
        meal.put("meal_date",today); meal.put("meal_slot","중식");
        when(mapper.weekMeals(anyMap())).thenReturn(List.of(weekMeal(1,today,"DRAFT")));
        assertThatThrownBy(()->service.completeWeek(new HashMap<>(Map.of("account_id","A","table_id","T","meals",List.of(Map.of("meal_service_id",9))))))
            .hasMessageContaining("확정할 수 없는 식사");
        balance.put("current_base_qty",new BigDecimal("100"));
        assertThatThrownBy(()->service.completeWeek(new HashMap<>(Map.of("account_id","A","table_id","T"))))
            .hasMessageContaining(today+" 중식").hasMessageContaining("재고가 부족합니다");
    }
}
