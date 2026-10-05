package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyMap;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.MealPlanV2Mapper;

class MealPlanV2ServiceTest {
    private final MealPlanV2Mapper mapper=mock(MealPlanV2Mapper.class);
    private final MealPlanV2Service service=new MealPlanV2Service(mapper,mock(MealUsageService.class));
    private Map<String,Object> payload() {
        return new HashMap<>(Map.of("table_id","PLAN","account_id","ACCOUNT","table_year",2026,"table_month",9,"table_week",2,
            "planned_servings",30,"meals",List.of(Map.of("meal_date","2026-09-15","meal_slot","lunch","meal_slot_code",2,
                "menus",List.of(Map.of("menu_id","RICE","menu_name","쌀밥"))))));
    }
    private void setup() {
        when(mapper.selectMealBudget(anyMap())).thenReturn(Map.of("budget_per_person",10000,"budget_source","DIET_PRICE"));
        doAnswer(invocation->{Map<String,Object> p=invocation.getArgument(0);p.put("meal_service_id",1);return 1;}).when(mapper).insertService(anyMap());
        doAnswer(invocation->{Map<String,Object> p=invocation.getArgument(0);p.put("meal_detail_id",1);return 1;}).when(mapper).insertDetail(anyMap());
        when(mapper.countCompatibleMenu(anyMap())).thenReturn(1);
        when(mapper.selectServiceCostValidation(anyMap())).thenReturn(Map.of("menu_count",1,"recipe_menu_count",1,"ingredient_count",1,"priced_ingredient_count",1,"meal_cost_per_person",1000,"meal_budget_per_person",10000));
    }
    @Test void savesCompletePlanWithSelectedServingsAndRecalculatesRequirements() {
        setup();assertThat(service.saveCompletePlan(payload()).get("id")).isEqualTo("PLAN");
        verify(mapper).insertService(argThat(p->p.get("planned_servings").equals(30)&&p.get("account_id").equals("ACCOUNT")));
        verify(mapper).insertRequirements(anyMap());verify(mapper).insertCosts(anyMap());
    }
    @Test void failurePropagatesWithDateAndMenuInsteadOfReportingSuccess() {
        setup();when(mapper.countCompatibleMenu(anyMap())).thenReturn(0);
        assertThatThrownBy(()->service.saveCompletePlan(payload())).hasMessageContaining("2026-09-15").hasMessageContaining("쌀밥");
        verify(mapper,never()).insertRequirements(anyMap());
    }
    @Test void missingServingsDoesNotCreateAHeader() {
        var p=payload();p.remove("planned_servings");
        assertThatThrownBy(()->service.saveCompletePlan(p)).hasMessageContaining("예정 식수");
        verify(mapper,never()).insertPlan(anyMap());
    }
}
