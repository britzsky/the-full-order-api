package com.example.demo.controller;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.example.demo.service.MenuService;
import com.example.demo.service.AccountMenuRecipeService;
import com.fasterxml.jackson.databind.SerializationFeature;

class MenuControllerJsonTest {
    private final MenuService service = mock(MenuService.class);
    private final AccountMenuRecipeService accountRecipes = mock(AccountMenuRecipeService.class);
    private MockMvc mvc;
    private final List<Map<String, Object>> rows = List.of(Map.of(
            "created_at", LocalDateTime.of(2026, 9, 10, 9, 30),
            "effective_date", LocalDate.of(2026, 9, 10),
            "purchase_price", new BigDecimal("12000.50"), "ingredient_name", "쌀"));

    @BeforeEach void setup() {
        var mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        var controller = new MenuController(service, accountRecipes, "unused", mapper);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @Test void allMenuListsSerializeJavaTimeValuesAsJsonArrays() throws Exception {
        when(service.MenuList(anyMap())).thenReturn(rows);
        when(service.DetailList(anyMap())).thenReturn(rows);
        when(service.IngredientsList(anyMap())).thenReturn(rows);
        when(service.AccountMenuList(anyMap())).thenReturn(rows);
        when(service.AccountDetailList(anyMap())).thenReturn(rows);
        when(service.AccountIngredientsList(anyMap())).thenReturn(rows);
        when(service.LikeMenuList(anyMap())).thenReturn(rows);
        when(service.LikeIngredientsList(anyMap())).thenReturn(rows);
        for (String path : List.of("MenuList", "DetailList", "IngredientsList", "AccountMenuList",
                "AccountDetailList", "AccountIngredientsList", "LikeMenuList", "LikeIngredientsList")) {
            mvc.perform(get("/Menu/" + path)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$[0].created_at").value("2026-09-10T09:30:00"))
                    .andExpect(jsonPath("$[0].effective_date").value("2026-09-10"))
                    .andExpect(jsonPath("$[0].purchase_price").value(12000.50))
                    .andExpect(jsonPath("$[0].ingredient_name").value("쌀"));
        }
    }

    @Test void recipeRetainsItsNestedResponseAndSerializesImageDates() throws Exception {
        when(service.RecipeList(anyMap())).thenReturn(rows);
        when(service.ImageList(anyMap())).thenReturn(rows);
        when(service.VideoList(anyMap())).thenReturn(List.of());
        mvc.perform(get("/Menu/RecipeList")).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipe.created_at").value("2026-09-10T09:30:00"))
                .andExpect(jsonPath("$.recipe.images[0].effective_date").value("2026-09-10"))
                .andExpect(jsonPath("$.recipe.videos").isEmpty())
                .andExpect(jsonPath("$.meta.source").value("local"));
    }
    @Test void legacyMenuSaveAcceptsIngredientsWithoutSupplierFields() throws Exception {
        when(service.AccountMenuSave(anyMap())).thenReturn(1);
        when(service.AccountIngredientsSave(anyMap())).thenReturn(1);
        mvc.perform(post("/Menu/AccountMenuSave").contentType(MediaType.APPLICATION_JSON).content("""
            {"added_menus":[{"account_id":"A","menu_id":"M","menu_name":"메뉴"}],
             "menu_details":[{"account_id":"A","menu_id":"M","ingredient_id":"I","qty_num":50,"qty_unit":"g"}]}
            """))
            .andExpect(status().isOk());
        verify(accountRecipes).ensureMenuIngredient(anyMap());
        verify(accountRecipes,never()).ensureIngredientInventory(anyMap());
        verify(service).AccountIngredientsSave(anyMap());
    }
}
