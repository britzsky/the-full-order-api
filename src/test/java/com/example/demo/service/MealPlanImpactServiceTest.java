package com.example.demo.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class MealPlanImpactServiceTest {
    Map<String,Object> row(String day,Object quantity,String unit) { return Map.of("ingredient_id","I","ingredient_name","감자","meal_date",day,"quantity",quantity,"base_unit",unit,"master_unit","g"); }
    @Test void shiftOfSameAmountStillShowsEarlierReductionAndLaterIncrease() {
        var result=MealPlanImpactService.diff(List.of(row("2026-10-03",1,"kg")),List.of(row("2026-10-04",1000,"g")));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("reduced_required_qty").toString()).isEqualTo("1000");
        assertThat(result.get(1).get("additional_required_qty").toString()).isEqualTo("1000");
    }
    @Test void sumsMenuRequirementsAndDoesNotInventDifferenceForUnitChanges() {
        assertThat(MealPlanImpactService.diff(List.of(row("2026-10-03",1,"kg")),List.of(row("2026-10-03",500,"g"),row("2026-10-03",500,"g")))).isEmpty();
    }
}
