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
import com.example.demo.service.S3FileStorageService;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import com.fasterxml.jackson.databind.SerializationFeature;

class MenuControllerJsonTest {
    private final MenuService service = mock(MenuService.class);
    private final AccountMenuRecipeService accountRecipes = mock(AccountMenuRecipeService.class);
    private final S3FileStorageService storage = mock(S3FileStorageService.class);
    private MockMvc mvc;
    private final List<Map<String, Object>> rows = List.of(Map.of(
            "created_at", LocalDateTime.of(2026, 9, 10, 9, 30),
            "effective_date", LocalDate.of(2026, 9, 10),
            "purchase_price", new BigDecimal("12000.50"), "ingredient_name", "쌀"));

    @BeforeEach void setup() {
        var mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        var controller = new MenuController(service, accountRecipes, storage, mapper);
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
        for (String path : List.of("MenuList", "DetailList", "IngredientsList", "AccountMenuList",
                "AccountDetailList", "AccountIngredientsList", "LikeMenuList")) {
            String query = path.equals("AccountMenuList") || path.equals("LikeMenuList") ? "?account_id=A" : "";
            mvc.perform(get("/Menu/" + path + query)).andExpect(status().isOk())
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
        verify(service).AccountIngredientsSave(anyMap());
    }

    /** 레시피 사진: 새 사진(data URL)은 저장소에 올리고, 이미 있는 사진은 다시 올리지 않고 경로를 그대로 쓴다(2026-11-16 S3 전환) */
    @SuppressWarnings("unchecked")
    @Test void recipeImagesGoThroughFileStorage() throws Exception {
        when(storage.uploadBytes(any(byte[].class), eq("a.png"), eq("image/png"), eq("recipe"))).thenReturn("/image/recipe/u_a.png");
        when(storage.location(anyString())).thenAnswer(i -> "s3://b/uploads" + i.getArgument(0));
        when(storage.exists("/image/recipe/old.jpg")).thenReturn(true);
        when(storage.metadata("/image/recipe/old.jpg")).thenReturn(new S3FileStorageService.StoredObjectMetadata("old.jpg", "image/jpeg", 77));
        when(service.RecipeSave(anyMap(), anyList(), anyList())).thenReturn(1);

        mvc.perform(post("/Menu/RecipeSave").contentType(MediaType.APPLICATION_JSON).content("""
            {"menu_id":"MENU001","menu_name":"콩나물무침","recipe_id":10001,
             "images":[{"file_name":"a.png","data_url":"data:image/png;base64,AQID"},
                       {"file_name":"old.jpg","file_url":"/image/recipe/old.jpg"}]}
            """)).andExpect(status().isOk());

        verify(storage).uploadBytes(eq(new byte[] { 1, 2, 3 }), eq("a.png"), eq("image/png"), eq("recipe"));
        verify(storage, times(1)).uploadBytes(any(), any(), any(), any());
        ArgumentCaptor<List<Map<String, Object>>> images = ArgumentCaptor.forClass(List.class);
        verify(service).RecipeSave(anyMap(), images.capture(), anyList());
        assertThat(images.getValue()).hasSize(2);
        assertThat(images.getValue().get(0)).containsEntry("file_url", "/image/recipe/u_a.png")
            .containsEntry("file_path", "s3://b/uploads/image/recipe/u_a.png").containsEntry("file_size", 3L);
        assertThat(images.getValue().get(1)).containsEntry("file_url", "/image/recipe/old.jpg").containsEntry("file_size", 77L);
    }
}
