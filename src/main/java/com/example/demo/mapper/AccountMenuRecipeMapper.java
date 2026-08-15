package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountMenuRecipeMapper {
    List<Map<String,Object>> ingredients(Map<String,Object> p);
    int upsertIngredient(Map<String,Object> p);
    int deleteIngredient(Map<String,Object> p);
    List<Map<String,Object>> menus(Map<String,Object> p);
    int upsertMenu(Map<String,Object> p);
    int deleteMenu(Map<String,Object> p);
    List<Map<String,Object>> recipeDetails(Map<String,Object> p);
    int insertRecipeDetail(Map<String,Object> p);
    int updateRecipeDetail(Map<String,Object> p);
    int deleteRecipeDetail(Map<String,Object> p);
    int ensureSupplierProduct(Map<String,Object> p);
    Long supplierProductId(Map<String,Object> p);
    int ensureAccountProduct(Map<String,Object> p);
    Long accountIngredientProductId(Map<String,Object> p);
    int ensureInventory(Map<String,Object> p);
}
