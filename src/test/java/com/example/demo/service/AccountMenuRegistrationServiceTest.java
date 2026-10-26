package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.AccountMenuRecipeMapper;
import com.example.demo.mapper.AccountMenuRegistrationMapper;

/** 식단표 PDF에서 거래처 메뉴 등록: 필수값·이름 중복·본사 반영 조건 */
@SuppressWarnings("unchecked")
class AccountMenuRegistrationServiceTest {
    final AccountMenuRegistrationMapper mapper = mock(AccountMenuRegistrationMapper.class);
    final AccountMenuRecipeMapper recipeMapper = mock(AccountMenuRecipeMapper.class);
    final AccountMenuRegistrationService service =
        new AccountMenuRegistrationService(mapper, recipeMapper, new AccountMenuRecipeService(recipeMapper));

    Map<String, Object> body(Object... extra) {
        var b = new HashMap<String, Object>(Map.of("account_id", "A", "menu_name", "크림스프", "calories_per_serving", "150",
            "menu_type", "2", "menu_gubun", "13", "food_type", "6", "user_id", "u",
            "ingredients", List.of(new HashMap<>(Map.of("ingredient_id", "MILK", "qty_num", "150", "qty_unit", "ml")))));
        for (int i = 0; i < extra.length; i += 2) b.put(extra[i].toString(), extra[i + 1]);
        return b;
    }

    @Test void registersAccountOnlyMenuWithRecipe() {
        when(recipeMapper.ingredientBaseUnit(anyMap())).thenReturn("ml");
        when(recipeMapper.accountIngredientBaseUnit(anyMap())).thenReturn("ml");
        var result = service.register(body());
        assertThat(result.get("menu_id").toString()).matches("AM-[0-9A-Z]+").hasSizeLessThanOrEqualTo(20);
        verify(recipeMapper).upsertMenu(argThat(m -> "크림스프".equals(m.get("menu_name")) && Integer.valueOf(13).equals(m.get("menu_gubun"))));
        verify(recipeMapper).ensureMenuIngredient(argThat(r -> "MILK".equals(r.get("ingredient_id"))));
        verify(recipeMapper).insertRecipeDetail(argThat(r -> "0".equals(r.get("review_flag"))
            && new java.math.BigDecimal("150").compareTo((java.math.BigDecimal) r.get("qty_per_person")) == 0));
    }

    @Test void ingredientsAndCaloriesAreRequired() {
        assertThatThrownBy(() -> service.register(body("ingredients", List.of()))).hasMessageContaining("식재료를 1개 이상");
        assertThatThrownBy(() -> service.register(body("calories_per_serving", "0"))).hasMessageContaining("칼로리");
        assertThatThrownBy(() -> service.register(body("menu_gubun", ""))).hasMessageContaining("메뉴구분");
        verify(recipeMapper, never()).upsertMenu(anyMap());
    }

    @Test void sameNameInAccountIsRejected() {
        when(mapper.activeAccountMenus(anyMap())).thenReturn(List.of(Map.of("menu_id", "M1", "menu_name", "크림 스프")));
        assertThatThrownBy(() -> service.register(body())).hasMessageContaining("같은 이름의 메뉴(크림 스프)");
    }

    @Test void unconvertibleUnitIsRejected() {
        when(recipeMapper.ingredientBaseUnit(anyMap())).thenReturn("g");
        when(recipeMapper.accountIngredientBaseUnit(anyMap())).thenReturn("g");
        assertThatThrownBy(() -> service.register(body("ingredients",
            List.of(new HashMap<>(Map.of("ingredient_id", "MILK", "ingredient_name_raw", "우유", "qty_num", "1", "qty_unit", "병"))))))
            .hasMessageContaining("우유의 단위(병)");
    }

    @Test void promoteMapsIngredientsByIdThenNameAndAddsTheRest() {
        when(mapper.accountMenu(anyMap())).thenReturn(Map.of("menu_id", "AM-1", "menu_name", "크림스프", "del_yn", "N"));
        when(mapper.standardMenu(anyMap())).thenReturn(null); // 아직 본사 메뉴 아님 (Mockito 기본값은 빈 Map)
        when(mapper.activeStandardMenus(anyMap())).thenReturn(List.of(Map.of("menu_id", "TMENU9", "menu_name", "크림 스프")));
        assertThatThrownBy(() -> service.promote(Map.of("account_id", "A", "menu_id", "AM-1"))).hasMessageContaining("TMENU9");

        when(mapper.activeStandardMenus(anyMap())).thenReturn(List.of());
        var same = new HashMap<String, Object>(Map.of("ingredient_id", "TIG1", "standard_ingredient_id", "TIG1", "ingredient_name", "우유", "base_unit", "ml"));
        var byName = new HashMap<String, Object>(Map.of("ingredient_id", "AI-1", "ingredient_name", "버터", "base_unit", "g"));
        var added = new HashMap<String, Object>(Map.of("ingredient_id", "AI-2", "ingredient_name", "궁채", "base_unit", "g"));
        byName.put("standard_ingredient_id", null);
        added.put("standard_ingredient_id", null);
        when(mapper.accountRecipe(anyMap())).thenReturn(List.of(same, byName, added));
        when(mapper.standardIngredients(anyMap())).thenReturn(List.of(Map.of("ingredient_id", "TIG7", "ingredient_name_std", "버터", "ingredient_name_raw", "버터", "base_unit", "g"),
            Map.of("ingredient_id", "TIG8", "ingredient_name_std", "궁채", "ingredient_name_raw", "궁채", "base_unit", "kg"))); // 단위가 다르면 같은 식재료로 보지 않음

        var preview = (List<Map<String, Object>>) service.promotePreview(Map.of("account_id", "A", "menu_id", "AM-1")).get("ingredients");
        assertThat(preview).extracting(r -> r.get("action")).containsExactly("SAME", "MATCHED", "NEW");
        assertThat(preview).extracting(r -> r.get("standard_ingredient_id")).containsExactly("TIG1", "TIG7", "AI-2");

        service.promote(Map.of("account_id", "A", "menu_id", "AM-1"));
        verify(mapper, times(1)).insertStandardIngredient(argThat(r -> "AI-2".equals(r.get("ingredient_id"))));
        verify(mapper).insertStandardMenu(anyMap());
        verify(mapper, times(3)).insertStandardRecipeRow(anyMap());
        verify(mapper).insertStandardRecipeRow(argThat(r -> "TIG7".equals(r.get("standard_ingredient_id"))));
    }

    @Test void accountIngredientNeedsUniqueNameAndKnownUnit() {
        when(mapper.accountIngredients(anyMap())).thenReturn(List.of(Map.of("ingredient_id", "TIG1", "ingredient_name", "양파")));
        assertThatThrownBy(() -> service.registerIngredient(Map.of("account_id", "A", "ingredient_name", " 양 파", "base_unit", "g"))).hasMessageContaining("이미 있는 식재료");
        assertThatThrownBy(() -> service.registerIngredient(Map.of("account_id", "A", "ingredient_name", "궁채", "base_unit", "봉"))).hasMessageContaining("g·ml·EA");
        var result = service.registerIngredient(Map.of("account_id", "A", "ingredient_name", "궁채", "base_unit", "g"));
        assertThat(result.get("ingredient_id").toString()).startsWith("AI-");
        verify(recipeMapper).upsertIngredient(argThat(r -> "궁채".equals(r.get("ingredient_name_std")) && "g".equals(r.get("base_unit"))));
    }

    @Test void importStandardRefusesMenuWithoutRecipe() {
        when(mapper.standardMenu(anyMap())).thenReturn(Map.of("menu_id", "TMENU1", "menu_name", "잡곡밥", "del_yn", "N"));
        assertThatThrownBy(() -> service.importStandard(Map.of("account_id", "A", "menu_id", "TMENU1"))).hasMessageContaining("식재료가 없어");
        verify(recipeMapper, never()).upsertMenu(anyMap());
    }

    @Test void resetToStandardReplacesAccountRecipeWithStandard() {
        when(mapper.accountMenu(anyMap())).thenReturn(Map.of("menu_id", "TMENU1", "menu_name", "잡곡밥", "del_yn", "N"));
        when(mapper.standardMenu(anyMap())).thenReturn(Map.of("menu_id", "TMENU1", "menu_name", "잡곡밥", "del_yn", "N"));
        when(mapper.standardRecipe(anyMap())).thenReturn(List.of(
                Map.of("recipe_id", 7, "menu_id", "TMENU1", "ingredient_id", "RICE", "ingredient_seq", 1, "qty_per_person", 80),
                Map.of("recipe_id", 7, "menu_id", "TMENU1", "ingredient_id", "BARLEY", "ingredient_seq", 2, "qty_per_person", 20)));
        when(recipeMapper.deleteMenuRecipe(anyMap())).thenReturn(3);
        when(recipeMapper.accountIngredientExists(anyMap())).thenReturn(1);
        var result = service.resetToStandard(Map.of("account_id", "A", "menu_id", "TMENU1", "user_id", "U"));
        var order = inOrder(recipeMapper);
        order.verify(recipeMapper).deleteMenuRecipe(argThat(p -> "A".equals(p.get("account_id")) && "TMENU1".equals(p.get("menu_id"))));
        order.verify(recipeMapper, times(2)).insertRecipeDetail(argThat(r -> "A".equals(r.get("account_id")) && "U".equals(r.get("user_id"))));
        verify(recipeMapper, never()).upsertMenu(anyMap());
        assertThat(result).containsEntry("removed_count", 3).containsEntry("copied_ingredient_count", 2);
    }

    @Test void resetToStandardRefusesAccountOnlyMenu() {
        when(mapper.accountMenu(anyMap())).thenReturn(Map.of("menu_id", "AM-1", "menu_name", "크림스프", "del_yn", "N"));
        when(mapper.standardMenu(anyMap())).thenReturn(null);
        assertThatThrownBy(() -> service.resetToStandard(Map.of("account_id", "A", "menu_id", "AM-1"))).hasMessageContaining("되돌릴 본사 레시피가 없습니다");
        verify(recipeMapper, never()).deleteMenuRecipe(anyMap());
    }
}
