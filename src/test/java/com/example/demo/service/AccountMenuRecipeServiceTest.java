package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.AccountMenuRecipeMapper;

class AccountMenuRecipeServiceTest {
    @Test void registerRecipeWithoutSupplierDoesNotCreateProductsOrInventory() {
        var mapper=mock(AccountMenuRecipeMapper.class);
        when(mapper.ingredientBaseUnit(anyMap())).thenReturn("g");
        var ingredient=new HashMap<String,Object>(Map.of("ingredient_id","I","base_unit","g","qty_base",50,"qty_num",50,"qty_unit","g"));
        var body=new HashMap<String,Object>(Map.of("account_id","A","menu_id","M","menu_name","메뉴","recipe_id",1,"ingredients",List.of(ingredient)));
        assertThat(new AccountMenuRecipeService(mapper).registerMenu(body).get("code")).isEqualTo(200);
        verify(mapper).ensureMenuIngredient(argThat(p -> p.get("account_id").equals("A") && p.get("ingredient_id").equals("I")));
        verify(mapper).insertRecipeDetail(anyMap());
        verify(mapper,never()).ensureSupplierProduct(anyMap());
        verify(mapper,never()).ensureAccountProduct(anyMap());
        verify(mapper,never()).ensureInventory(anyMap());
    }
    @Test void ingredientListRequiresAccount() {
        var mapper=mock(AccountMenuRecipeMapper.class);
        assertThatThrownBy(() -> new AccountMenuRecipeService(mapper).ingredients(Map.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }
}
