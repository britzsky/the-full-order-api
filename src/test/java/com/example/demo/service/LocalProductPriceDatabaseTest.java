package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.InventoryMapper;
import com.example.demo.mapper.SupplierCatalogMapper;
import com.example.demo.mapper.MenuMapper;
import com.example.demo.mapper.AccountMenuRecipeMapper;
import com.example.demo.mapper.MealPlanV2Mapper;
import com.example.demo.mapper.ShortageProcurementMapper;
import com.example.demo.mapper.OrderWorkflowMapper;

/** Real local MySQL regression, with isolated fixtures and unconditional rollback. */
@EnabledIfEnvironmentVariable(named="RUN_LOCAL_DB_TESTS", matches="true")
class LocalProductPriceDatabaseTest {
    @Test void manualProductAndRepeatedPriceEditsAreVisibleWithoutASiteConnection() throws Exception {
        Path resources=Path.of("src/main/resources");
        Properties props=new Properties();
        try(Reader reader=Files.newBufferedReader(resources.resolve("application.properties"))){props.load(reader);}
        for(String imported:props.getProperty("spring.config.import","").split(",")) {
            Path path=resources.resolve(imported.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(path.startsWith(resources)&&Files.isRegularFile(path))
                try(Reader reader=Files.newBufferedReader(path)){props.load(reader);}
        }
        String url=props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        var ds=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"));
        var cfg=new Configuration(new Environment("product-price-rollback",new JdbcTransactionFactory(),ds));
        for(String name:List.of("InventoryMapper","SupplierCatalogMapper","MenuMapper","AccountMenuRecipeMapper","MealPlanV2Mapper","ShortageProcurementMapper","OrderWorkflowMapper")) {
            String resource="mybatis-mapper/"+name+".xml";
            try(var stream=Files.newInputStream(resources.resolve(resource))){new XMLMapperBuilder(stream,cfg,resource,cfg.getSqlFragments()).parse();}
        }
        try(SqlSession session=new SqlSessionFactoryBuilder().build(cfg).openSession(false)) {
            try {
                var mapper=session.getMapper(SupplierCatalogMapper.class);
                var inventory=session.getMapper(InventoryMapper.class);
                var service=new SupplierCatalogService(mapper);
                String id="T"+UUID.randomUUID().toString().replace("-","").substring(0,12);
                Map<String,Object> supplier=new HashMap<>(Map.of("supplier_code",id,"supplier_name","가격 회귀 검증"));
                service.createSupplier(supplier);
                Map<String,Object> ingredient=new HashMap<>(Map.of("ingredient_id",id,"ingredient_name","가격 회귀 검증","base_unit","g","order_unit","BOX","convert_value",2000));
                new InventoryService(inventory, org.mockito.Mockito.mock(StockAvailabilityService.class)).createIngredientMaster(ingredient);
                var menus=session.getMapper(MenuMapper.class);
                var menuService=new MenuService(menus, org.mockito.Mockito.mock(StockAvailabilityService.class), org.mockito.Mockito.mock(AccountMenuRecipeService.class));
                Map<String,Object> menu=new HashMap<>(Map.of("menu_id",id,"menu_name","가격 연동 검증","food_type",1));
                menuService.MenuSave(menu);
                Map<String,Object> recipe=new HashMap<>(Map.of("menu_id",id,"ingredient_id",id,"ingredient_name","가격 회귀 검증",
                        "ingredient_seq",1,"qty_num",300,"qty_unit","g","recipe_yield_servings",2));
                menuService.IngredientsSave(recipe);
                assertThat(menuRow(menus,id).get("menu_cost_per_person")).isNull();
                Map<String,Object> body=new HashMap<>(Map.of("supplier_id",supplier.get("supplier_id"),"ingredient_id",id,"supplier_item_code",id,
                        "product_name","검증 상품","order_unit","BOX","package_qty",2,"package_unit","kg","base_qty",2,"base_unit","kg","purchase_price",10000));
                service.createProductWithPrice(body);
                Object productId=body.get("supplier_product_id");
                Map<String,Object> query=Map.of("account_id","ROLLBACK_ONLY","ingredient_id",id);
                assertVisible(inventory,query,productId,"10000","2000");
                assertMenuCost(menus,id,"750");
                body.put("product_name","수정 상품");body.put("purchase_price",12000);
                body.put("base_qty",3);body.put("base_unit","kg");body.put("package_qty",3);
                service.updateProductWithPrice(body);
                assertVisible(inventory,query,productId,"12000","3000");
                assertMenuCost(menus,id,"600");
                assertThat(inventory.ingredientMasterList(query).get(0).get("product_name")).isEqualTo("수정 상품");
                body.put("purchase_price",9000);
                service.updateProductWithPrice(body);
                assertVisible(inventory,query,productId,"9000","3000");
                assertMenuCost(menus,id,"450");
                assertThat(mapper.products(Map.of("ingredient_id",id))).hasSize(1);
                validateAccountMealAndInventory(session,body,recipe,menu,id);
                body.put("base_unit","ml");
                assertThatThrownBy(()->service.updateProductWithPrice(body)).isInstanceOf(IllegalArgumentException.class);
                assertVisible(inventory,query,productId,"9000","3000");
                body.put("base_unit","g");body.put("supplier_item_code","CHANGED");
                assertThatThrownBy(()->service.updateProductWithPrice(body)).isInstanceOf(IllegalArgumentException.class);
                assertThat(mapper.products(Map.of("ingredient_id",id))).hasSize(2);
            } finally { session.rollback(); }
        }
    }

    private void validateAccountMealAndInventory(SqlSession session,Map<String,Object> product,
            Map<String,Object> recipe,Map<String,Object> menu,String id) throws Exception {
        String accountId;
        try(var statement=session.getConnection().createStatement();var rows=statement.executeQuery(
                "SELECT account_id FROM the_full_order.tb_account_ingredient_product LIMIT 1")) {
            assertThat(rows.next()).isTrue();accountId=rows.getString(1);
        }
        var menus=session.getMapper(MenuMapper.class);
        var account=new AccountMenuRecipeService(session.getMapper(AccountMenuRecipeMapper.class));
        var catalog=new SupplierCatalogService(session.getMapper(SupplierCatalogMapper.class));
        // An older unpriced link must not hide a later product with a management price.
        Map<String,Object> unpriced=new HashMap<>(product);
        unpriced.remove("supplier_product_id");unpriced.put("supplier_item_code",id+"X");
        catalog.createProduct(unpriced);
        unpriced.put("account_id",accountId);account.ensureIngredientInventory(unpriced);
        Map<String,Object> linked=new HashMap<>(product);linked.put("account_id",accountId);
        account.ensureIngredientInventory(linked);
        var inventory=session.getMapper(InventoryMapper.class);
        // Inventory rows identify their products through account_ingredient_product_id.
        var balance=inventory.AccountInventoryList(Map.of("account_id",accountId)).stream()
                .filter(row->linked.get("account_ingredient_product_id").toString().equals(row.get("account_ingredient_product_id").toString())).findFirst().orElseThrow();
        assertThat(balance.get("stock_status")).isEqualTo("EMPTY");
        Map<String,Object> stockUpdate=new HashMap<>(Map.of("inventory_balance_id",balance.get("inventory_balance_id"),"current_base_qty",100));
        inventory.updateInventory(stockUpdate);
        assertThat(inventory.AccountInventoryList(Map.of("account_id",accountId,"inventory_balance_id",balance.get("inventory_balance_id"))).get(0).get("stock_status")).isEqualTo("UNKNOWN");
        Map<String,Object> accountMenu=new HashMap<>(menu);accountMenu.put("account_id",accountId);
        account.saveMenu(accountMenu);
        Map<String,Object> accountRecipe=new HashMap<>(recipe);accountRecipe.put("account_id",accountId);
        account.createRecipe(accountRecipe);
        var cost=menus.AccountMenuList(Map.of("account_id",accountId)).stream().filter(row->id.equals(row.get("menu_id"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(cost.get("menu_cost_per_person").toString())).isEqualByComparingTo("450");
        assertThat(cost.get("menu_cost_complete").toString()).isEqualTo("1");
        var detail=menus.AccountDetailList(Map.of("account_id",accountId,"menu_id",id,"servingQty",1)).get(0);
        assertThat(detail.get("supplier_product_id").toString()).isEqualTo(product.get("supplier_product_id").toString());
        assertThat(new BigDecimal(detail.get("purchase_price").toString())).isEqualByComparingTo("9000");
        var meals=session.getMapper(MealPlanV2Mapper.class);
        var draftProcurement=new ShortageProcurementService(session.getMapper(ShortageProcurementMapper.class),null,new com.fasterxml.jackson.databind.ObjectMapper());
        var draftQuery=new HashMap<String,Object>(Map.of("account_id",accountId,"source","DRAFT","draft_meals",List.of(Map.of("menu_id",id,"planned_servings",10),Map.of("menu_id",id,"planned_servings",20))));
        var draftResult=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(draftProcurement.shortages(draftQuery)).path("ingredients");
        assertThat(draftResult.size()).isEqualTo(1);
        assertThat(draftResult.get(0).path("required_qty").decimalValue()).isEqualByComparingTo("4500");
        assertThat(draftResult.get(0).path("shortage_qty").decimalValue()).isEqualByComparingTo("4400");
        Map<String,Object> plan=new HashMap<>(Map.of("table_id",id,"account_id",accountId,"account_name","검증", "table_year","2026","table_month","09","table_week","2"));
        meals.insertPlan(plan);
        plan.put("meal_date",java.time.LocalDate.now().plusDays(1).toString());plan.put("weekday",5);plan.put("meal_slot","LUNCH");plan.put("meal_slot_code",2);
        plan.put("planned_servings",10);plan.put("meal_budget_per_person",100000);plan.put("budget_source","TEST");
        meals.insertService(plan);plan.put("menu_id",id);plan.put("menu_name","검증");
        meals.insertDetail(plan);
        var validation=meals.selectServiceCostValidation(plan);
        assertThat(validation.get("priced_ingredient_count").toString()).isEqualTo("1");
        assertThat(new BigDecimal(validation.get("meal_cost_per_person").toString())).isEqualByComparingTo("450");
        meals.insertRequirements(plan);meals.insertCosts(plan);
        var requirement=meals.requirements(plan).get(0);
        assertThat(new BigDecimal(requirement.get("calculated_cost").toString())).isEqualByComparingTo("4500");
        assertThat(requirement.get("supplier_product_id").toString()).isEqualTo(product.get("supplier_product_id").toString());
        assertThat(new BigDecimal(meals.costs(plan).get(0).get("cost_per_person").toString())).isEqualByComparingTo("450");
        var checkpoint=session.getConnection().setSavepoint();
        try {
            Map<String,Object> noPrice=new HashMap<>(product);noPrice.put("purchase_price",0);
            session.getMapper(SupplierCatalogMapper.class).updatePrice(noPrice);
            var missing=menus.AccountMenuList(Map.of("account_id",accountId)).stream().filter(row->id.equals(row.get("menu_id"))).findFirst().orElseThrow();
            assertThat(missing.get("menu_cost_complete").toString()).isEqualTo("0");
            assertThat(missing.get("menu_cost_reason").toString()).contains("가격 확인");
            assertThat(meals.selectServiceCostValidation(plan).get("priced_ingredient_count").toString()).isEqualTo("0");
        } finally {session.getConnection().rollback(checkpoint);session.clearCache();}
        validateShortageAggregation(session,plan,linked,id);
    }

    private void validateShortageAggregation(SqlSession session,Map<String,Object> plan,Map<String,Object> product,String id) {
        var meals=session.getMapper(MealPlanV2Mapper.class);
        Map<String,Object> second=new HashMap<>(plan);second.put("meal_date",java.time.LocalDate.now().plusDays(2).toString());second.put("planned_servings",20);
        meals.insertService(second);meals.insertDetail(second);meals.insertRequirements(second);
        Map<String,Object> warehouse=new HashMap<>(product);warehouse.put("location_id","L001");warehouse.put("current_base_qty",150);
        session.getMapper(InventoryMapper.class).insertInventory(warehouse);
        var aggregation=new ShortageProcurementService(session.getMapper(ShortageProcurementMapper.class),null,new com.fasterxml.jackson.databind.ObjectMapper());
        Map<String,Object> query=new HashMap<>(Map.of("account_id",plan.get("account_id"),"table_id",id,"source","PLAN",
                "date_from",java.time.LocalDate.now().toString(),"date_to",java.time.LocalDate.now().plusDays(3).toString()));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var before=json.valueToTree(aggregation.shortages(query)).path("ingredients");
        assertThat(before.size()).isEqualTo(1);
        assertThat(before.get(0).path("required_qty").decimalValue()).isEqualByComparingTo("4500");
        assertThat(before.get(0).path("current_qty").decimalValue()).isEqualByComparingTo("250");
        assertThat(before.get(0).path("shortage_qty").decimalValue()).isEqualByComparingTo("4250");
        Map<String,Object> order=new HashMap<>(product);order.put("purchase_order_id",UUID.randomUUID().toString());order.put("client_ord","TEST"+id);
        order.put("requested_delivery_date",java.time.LocalDate.now().plusDays(1).toString());order.put("total_amount",9000);
        order.put("order_qty",1);order.put("client_ord_item","1");order.put("line_amount",9000);
        var orders=session.getMapper(OrderWorkflowMapper.class);orders.insertOrder(order);orders.insertItem(order);
        var after=json.valueToTree(aggregation.shortages(query)).path("ingredients").get(0);
        assertThat(after.path("incoming_qty").decimalValue()).isEqualByComparingTo("3000");
        assertThat(after.path("shortage_qty").decimalValue()).isEqualByComparingTo("1250");
        order.put("status","FAILED");orders.updateOrder(order);
        var failed=json.valueToTree(aggregation.shortages(query)).path("ingredients").get(0);
        assertThat(failed.path("incoming_qty").decimalValue()).isZero();
        assertThat(failed.path("shortage_qty").decimalValue()).isEqualByComparingTo("4250");
        Map<String,Object> today=new HashMap<>(plan);
        today.put("meal_date",java.time.LocalDate.now().toString());today.put("planned_servings",20);
        meals.insertService(today);meals.insertDetail(today);meals.insertRequirements(today);
        query.remove("source");
        var inventory=json.valueToTree(aggregation.shortages(query));
        assertThat(inventory.path("criteria").path("source").asText()).isEqualTo("INVENTORY");
        var matching=java.util.stream.StreamSupport.stream(inventory.path("ingredients").spliterator(),false)
                .filter(row->id.equals(row.path("ingredient_id").asText())).findFirst().orElseThrow();
        assertThat(matching.path("required_qty").decimalValue()).isEqualByComparingTo("3000");
        assertThat(matching.path("shortage_qty").decimalValue()).isEqualByComparingTo("3050");
    }

    private void assertVisible(InventoryMapper inventory,Map<String,Object> query,Object id,String price,String quantity) {
        var rows=inventory.ingredientMasterList(query);
        assertThat(rows).hasSize(1);
        var row=rows.get(0);
        assertThat(row.get("supplier_product_id").toString()).isEqualTo(id.toString());
        assertThat(new BigDecimal(row.get("purchase_price").toString())).isEqualByComparingTo(price);
        assertThat(new BigDecimal(row.get("package_base_qty").toString())).isEqualByComparingTo(quantity);
    }

    private Map<String,Object> menuRow(MenuMapper menus,String id) {
        return menus.MenuList(Map.of("account_id","ROLLBACK_ONLY")).stream()
                .filter(row -> id.equals(row.get("menu_id"))).findFirst().orElseThrow();
    }

    private void assertMenuCost(MenuMapper menus,String id,String expected) {
        assertThat(new BigDecimal(menuRow(menus,id).get("menu_cost_per_person").toString()))
                .isEqualByComparingTo(expected);
    }
}
