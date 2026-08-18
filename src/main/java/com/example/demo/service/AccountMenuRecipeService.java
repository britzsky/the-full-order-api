package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.AccountMenuRecipeMapper;

@Service
public class AccountMenuRecipeService {
    private static final AtomicLong RECIPE_ID_SEQUENCE = new AtomicLong(System.currentTimeMillis());
    private final AccountMenuRecipeMapper mapper;
    public AccountMenuRecipeService(AccountMenuRecipeMapper mapper) { this.mapper=mapper; }
    public List<Map<String,Object>> ingredients(Map<String,Object> p){return mapper.ingredients(p);}
    public List<Map<String,Object>> menus(Map<String,Object> p){return mapper.menus(p);}
    public List<Map<String,Object>> recipes(Map<String,Object> p){return mapper.recipeDetails(p);}

    public Map<String,Object> saveIngredient(Map<String,Object> b){require(b,"account_id","ingredient_id","base_unit"); return result(mapper.upsertIngredient(b));}
    public Map<String,Object> deleteIngredient(Map<String,Object> b){require(b,"account_id","ingredient_id"); return result(mapper.deleteIngredient(b));}
    public Map<String,Object> saveMenu(Map<String,Object> b){require(b,"account_id","menu_id","menu_name"); validateMealPlanType(b); validateCalories(b); return result(mapper.upsertMenu(b));}
    public Map<String,Object> deleteMenu(Map<String,Object> b){require(b,"account_id","menu_id"); return result(mapper.deleteMenu(b));}

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String,Object> registerMenu(Map<String,Object> b){
        require(b,"account_id","menu_id","menu_name","recipe_id");
        validateMealPlanType(b);
        validateCalories(b);
        int changed=mapper.upsertMenu(b);
        Object rows=b.get("ingredients");
        if(!(rows instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("ingredients is required.");
        for(Object value:list){
            if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Each ingredient must be an object.");
            Map<String,Object> row=(Map<String,Object>)value;
            row.put("account_id",b.get("account_id")); row.put("menu_id",b.get("menu_id")); row.put("recipe_id",b.get("recipe_id")); row.put("user_id",b.get("user_id"));
            require(row,"ingredient_id","base_unit","qty_base","supplier_id","supplier_item_code","product_name","order_unit","base_qty");
            mapper.upsertIngredient(row);
            normalize(row);
            mapper.insertRecipeDetail(row);
            ensureIngredientInventory(row);
            changed++;
        }
        return Map.of("code",200,"message","success","saved_count",changed,"menu_id",b.get("menu_id"));
    }

    public int ensureIngredientInventory(Map<String,Object> row){
        require(row,"account_id","ingredient_id","base_unit","supplier_id","supplier_item_code","product_name","order_unit","base_qty");
        mapper.upsertIngredient(row);
        mapper.ensureSupplierProduct(row);
        Long supplierProductId=mapper.supplierProductId(row);
        if(supplierProductId==null) throw new IllegalArgumentException("Supplier product could not be resolved.");
        row.put("supplier_product_id",supplierProductId);
        mapper.ensureAccountProduct(row);
        Long accountProductId=mapper.accountIngredientProductId(row);
        if(accountProductId==null) throw new IllegalArgumentException("Account supplier product could not be resolved.");
        row.put("account_ingredient_product_id",accountProductId);
        return mapper.ensureInventory(row);
    }

    public Object ensureRecipeId(Map<String,Object> row) {
        Object recipeId = row.get("recipe_id");
        if (recipeId != null && !recipeId.toString().isBlank()) return recipeId;

        require(row, "account_id", "menu_id");
        Long existingRecipeId = mapper.recipeId(row);
        long resolvedRecipeId = existingRecipeId != null ? existingRecipeId : RECIPE_ID_SEQUENCE.incrementAndGet();
        row.put("recipe_id", resolvedRecipeId);
        return resolvedRecipeId;
    }

    public Map<String,Object> createRecipe(Map<String,Object> b){require(b,"account_id","recipe_id","menu_id","ingredient_id","qty_base","base_unit"); normalize(b); mapper.insertRecipeDetail(b); return result(1);}
    public Map<String,Object> updateRecipe(Map<String,Object> b){require(b,"account_recipe_detail_id"); if(b.get("qty_base")!=null) normalize(b); return result(mapper.updateRecipeDetail(b));}
    public Map<String,Object> deleteRecipe(Map<String,Object> b){require(b,"account_recipe_detail_id"); return result(mapper.deleteRecipeDetail(b));}
    private void normalize(Map<String,Object>b){BigDecimal q=decimal(b.get("qty_base")); BigDecimal servings=decimal(b.get("recipe_yield_servings")); if(servings.signum()<=0)servings=BigDecimal.ONE; b.put("recipe_yield_servings",servings); b.put("qty_per_person",q.divide(servings,3,RoundingMode.HALF_UP));}
    private BigDecimal decimal(Object v){return v==null||v.toString().isBlank()?BigDecimal.ONE:new BigDecimal(v.toString());}
    private void require(Map<String,Object>b,String...fs){for(String f:fs)if(text(b.get(f)).isBlank())throw new IllegalArgumentException(f+" is required.");}
    private void validateMealPlanType(Map<String,Object>b){Object value=b.get("meal_plan_type");if(value==null||value.toString().isBlank())return;int type=Integer.parseInt(value.toString());if(type<0||type>5)throw new IllegalArgumentException("meal_plan_type must be between 0 and 5.");}
    private void validateCalories(Map<String,Object>b){Object value=b.get("calories_per_serving");if(value==null||value.toString().isBlank())return;if(new BigDecimal(value.toString()).signum()<0)throw new IllegalArgumentException("calories_per_serving cannot be negative.");}
    private String text(Object v){return v==null?"":v.toString();}
    private Map<String,Object> result(int n){if(n==0)throw new IllegalArgumentException("Target row was not found.");return Map.of("code",200,"message","success","affected",n);}
}
