package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.MealUsageMapper;

/** Creates only a uniquely named disposable localhost schema; never alters the application DB. */
@EnabledIfEnvironmentVariable(named="RUN_ISOLATED_MEAL_DB_TESTS",matches="true")
class LocalMealUsageDatabaseTest {
    @Test void realSqlPostingRollbackOriginsAndConcurrentRetry() throws Exception {
        Path resources=Path.of("src/main/resources");Properties props=new Properties();
        try(Reader r=Files.newBufferedReader(resources.resolve("application.properties"))){props.load(r);}
        for(String imp:props.getProperty("spring.config.import","").split(",")) {
            Path p=resources.resolve(imp.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(p.startsWith(resources)&&Files.isRegularFile(p))try(Reader r=Files.newBufferedReader(p)){props.load(r);}
        }
        String url=props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        String schema="test_meal_usage_"+UUID.randomUUID().toString().replace("-","");
        var admin=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"));
        try(Connection c=admin.getConnection();Statement st=c.createStatement()) {
            st.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                st.execute("USE "+schema);st.execute("SET FOREIGN_KEY_CHECKS=0");
                String ddl=Files.readString(Path.of("ddl-source/order_ddl_local.txt")).replace("the_full_order.",schema+".");
                Matcher tables=Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?(?:"+schema+"\\.)?(\\w+) \\([\\s\\S]*?\\) ENGINE[^;]*;").matcher(ddl);
                Set<String> needed=Set.of("tb_supplier","tb_supplier_ingredient_product","tb_account_ingredient_product","tb_account_inventory_balance","tb_account_inventory_movement","tb_account_inventory_lot","tb_account_meal_service","tb_account_meal_ingredient_requirement","tb_account_meal_stock_usage","tb_ingredient_master","tb_supplier_site_product_offer","tb_supplier_product_availability","tb_supplier_account_site","tb_account_table_meals","tb_account_menu_master","tb_account_recipe_detail","tb_account_table_meals_detail","tb_account_meal_menu_cost_snapshot","tb_supplier_ingredient_price_history","tb_supplier_site_product_price","tb_like_ingredients","tb_account_ingredient_master","tb_account_purchase_order","tb_account_purchase_order_item");
                Set<String> created=new HashSet<>();
                while(tables.find())if(needed.contains(tables.group(1))){st.execute(tables.group());created.add(tables.group(1));}
                assertThat(created).containsAll(needed);
                insert(c,schema,"tb_supplier",Map.of("supplier_id",1,"supplier_code","TEST"));
                insert(c,schema,"tb_ingredient_master",Map.of("ingredient_id","I","base_unit","g"));
                insert(c,schema,"tb_supplier_ingredient_product",Map.of("supplier_product_id",1,"supplier_id",1,"ingredient_id","I","base_unit","g","supplier_item_code","OLD","product_name","old product"));
                // 거래처 연결(mapped_ingredient_id)이 있어야 해당 식재료 재고로 인정된다. 본사 분류(ingredient_id)만으로는 연결되지 않는다.
                insert(c,schema,"tb_account_ingredient_product",Map.of("account_ingredient_product_id",1,"account_id","A","supplier_product_id",1,"mapped_ingredient_id","I"));
                insert(c,schema,"tb_account_table_meals",Map.of("table_id","T","account_id","A"));
                insert(c,schema,"tb_account_meal_service",Map.of("meal_service_id",1,"account_id","A","table_id","T","meal_date","2026-09-28","meal_slot","LUNCH","planned_servings",2));
                insert(c,schema,"tb_account_meal_ingredient_requirement",Map.of("meal_service_id",1,"menu_id","M","ingredient_id","I","supplier_product_id",1,"qty_per_person",100,"planned_servings",2,"total_required_qty",200,"base_unit","g"));
                insert(c,schema,"tb_account_inventory_balance",Map.of("inventory_balance_id",1,"account_id","A","account_ingredient_product_id",1,"current_base_qty",300,"base_unit","g","inventory_amount",30,"average_unit_cost",0.1));
                insert(c,schema,"tb_account_inventory_lot",Map.of("inventory_lot_id",1,"account_id","A","account_ingredient_product_id",1,"received_base_qty",300,"remaining_base_qty",300,"base_unit","g","origin_name_snapshot","old origin"));
                insert(c,schema,"tb_supplier_account_site",Map.of("supplier_account_site_id",1,"account_id","A","supplier_id",1));
                insert(c,schema,"tb_supplier_site_product_offer",Map.of("supplier_offer_id",1,"supplier_account_site_id",1,"supplier_product_id",1,"provider_attributes","{\"rawMaterialOrigin1\":\"saved origin\"}"));
                st.execute("SET FOREIGN_KEY_CHECKS=1");
                var ds=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url.replace("/the_full_order?","/"+schema+"?"),props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"));
                var cfg=new Configuration(new Environment("isolated",new SpringManagedTransactionFactory(),ds));
                for(String name:List.of("MenuMapper","MealPlanV2Mapper","MealUsageMapper","ShortageProcurementMapper","AccountMenuRecipeMapper","InventoryMapper","SupplierCatalogMapper","SupplierIntegrationMapper","ProcurementCrudMapper","ProcurementMapper")) {
                    String resource="mybatis-mapper/"+name+".xml";
                    String xml=Files.readString(resources.resolve(resource)).replace("the_full_order.",schema+".");
                    new XMLMapperBuilder(new StringReader(xml),cfg,resource,cfg.getSqlFragments()).parse();
                }
                var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
                var target=new MealUsageService(session.getMapper(MealUsageMapper.class));
                var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
                proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
                var service=(MealUsageService)proxy.getProxy();
                var p=new HashMap<String,Object>(Map.of("account_id","A","table_id","T","meal_service_id",1,"actual_servings",2));
                // Snapshot SQL must execute against the exact CREATE definitions.
                service.snapshot(p);assertThat(service.origins(p)).hasSize(1);
                assertThat(service.origins(p).get(0).get("origin_name")).isEqualTo("saved origin");
                st.execute("UPDATE tb_supplier_site_product_offer SET provider_attributes='{\"rawMaterialOrigin1\":\"changed current origin\"}'");
                service.snapshot(p);
                assertThat(service.origins(p).get(0).get("origin_name")).isEqualTo("saved origin");
                var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
                try {
                    var a=pool.submit(()->service.complete(new HashMap<>(p)));
                    var b=pool.submit(()->service.complete(new HashMap<>(p)));
                    a.get(20,java.util.concurrent.TimeUnit.SECONDS);b.get(20,java.util.concurrent.TimeUnit.SECONDS);
                } finally {pool.shutdownNow();}
                assertThat(number(st,"SELECT current_base_qty FROM tb_account_inventory_balance")).isEqualByComparingTo("100");
                assertThat(number(st,"SELECT COUNT(*) FROM tb_account_meal_stock_usage WHERE reversed_at IS NULL")).isEqualByComparingTo("1");
                assertThat(service.origins(p).get(0).get("origin_name")).isEqualTo("old origin");
                service.cancel(p);service.cancel(p);
                assertThat(number(st,"SELECT current_base_qty FROM tb_account_inventory_balance")).isEqualByComparingTo("300");
                assertThat(number(st,"SELECT remaining_base_qty FROM tb_account_inventory_lot")).isEqualByComparingTo("300");
                service.reopen(p);p.put("actual_servings",4);
                assertThatThrownBy(()->service.complete(p)).hasMessageContaining("재고가 부족");
                assertThat(number(st,"SELECT current_base_qty FROM tb_account_inventory_balance")).isEqualByComparingTo("300");
                assertThat(number(st,"SELECT remaining_base_qty FROM tb_account_inventory_lot")).isEqualByComparingTo("300");
                assertThat(number(st,"SELECT COUNT(*) FROM tb_account_meal_stock_usage WHERE reversed_at IS NULL")).isEqualByComparingTo("0");
                p.put("actual_servings",1);service.complete(p);
                assertThat(number(st,"SELECT current_base_qty FROM tb_account_inventory_balance")).isEqualByComparingTo("200");
                var criteria=new HashMap<String,Object>(p);criteria.put("date_from","2026-09-01");criteria.put("date_to","2026-10-01");criteria.put("average_days",30);
                assertThat(session.selectList("com.example.demo.mapper.ShortageProcurementMapper.demand",criteria)).isEmpty();
                assertThat(session.selectList("com.example.demo.mapper.ShortageProcurementMapper.history",criteria)).hasSize(1);
                criteria.put("menu_id","M");criteria.put("servingQty",1);
                for(String query:List.of("ShortageProcurementMapper.stock","ShortageProcurementMapper.targets","ShortageProcurementMapper.incoming","MenuMapper.AccountDetailList","AccountMenuRecipeMapper.origins","InventoryMapper.AccountInventoryList"))
                    assertThatCode(()->session.selectList("com.example.demo.mapper."+query,criteria)).as(query).doesNotThrowAnyException();
                // 본사 분류만 같은(거래처 미연결) 동기화 상품은 기준상품 해제 대상이 아니다. 같은 테이블 갱신+조회(MySQL 1093)도 함께 검증.
                insert(c,schema,"tb_supplier_ingredient_product",Map.of("supplier_product_id",2,"supplier_id",1,"ingredient_id","I","base_unit","g","supplier_item_code","SYNC","product_name","sync only"));
                insert(c,schema,"tb_account_ingredient_product",Map.of("account_ingredient_product_id",2,"account_id","A","supplier_product_id",2,"preferred_yn","Y"));
                st.execute("UPDATE tb_account_ingredient_product SET preferred_yn='Y' WHERE account_ingredient_product_id=1");
                session.update("com.example.demo.mapper.SupplierCatalogMapper.clearPreferredProduct",Map.of("account_id","A","supplier_product_id",1));
                assertThat(number(st,"SELECT COUNT(*) FROM tb_account_ingredient_product WHERE account_ingredient_product_id=1 AND preferred_yn='N'")).isEqualByComparingTo("1");
                assertThat(number(st,"SELECT COUNT(*) FROM tb_account_ingredient_product WHERE account_ingredient_product_id=2 AND preferred_yn='Y'")).isEqualByComparingTo("1");
                // 미연결 동기화 상품의 재고는 식재료 I의 재고로 잡히지 않는다
                // (예전 방식이면 본사 분류 I로 두 상품 모두 잡혀 2건)
                assertThat(session.selectList("com.example.demo.mapper.ShortageProcurementMapper.targets",criteria)).hasSize(1);
                // 연결 규격(link_base_*)이 상품 대표 규격보다 우선한다 (계란: 상품 1560g, 이 거래처 연결은 30EA)
                criteria.put("delivery_date","2026-09-28");criteria.put("average_days",14);
                for(String query:List.of("SupplierIntegrationMapper.offers","ProcurementCrudMapper.analysis","ProcurementMapper.mealPlanIngredientAnalysis","AccountMenuRecipeMapper.ingredients","SupplierCatalogMapper.accountProducts"))
                    assertThatCode(()->session.selectList("com.example.demo.mapper."+query,criteria)).as(query).doesNotThrowAnyException();
                st.execute("UPDATE tb_account_ingredient_product SET link_base_qty=30,link_base_unit='EA' WHERE account_ingredient_product_id=1");
                var inventoryRow=session.<Map<String,Object>>selectList("com.example.demo.mapper.InventoryMapper.AccountInventoryList",criteria).stream()
                    .filter(r->"1".equals(String.valueOf(r.get("account_ingredient_product_id")))).findFirst().orElseThrow();
                assertThat(new java.math.BigDecimal(inventoryRow.get("package_base_qty").toString())).isEqualByComparingTo("30");
                // 동기화: 규격 0으로 저장된 상품만 다시 채우고, 이미 규격이 있는 상품은 유지
                var product=new HashMap<String,Object>();
                product.put("supplier_id",1);product.put("ingredient_id",null);product.put("supplier_item_code","EGG");product.put("product_name","계란,대란,국산,30EA,1560G이상/PAC,Y");
                product.put("order_unit","PAC");product.put("package_qty",0);product.put("package_unit","PAC");product.put("base_qty",0);product.put("base_unit","PAC");
                product.put("alt_base_qty",null);product.put("alt_base_unit",null);product.put("minimum_order_qty",1);product.put("order_increment_qty",1);
                product.put("tax_type","");product.put("active_yn","Y");product.put("user_id","T");product.put("image_url",null);product.put("thumbnail_url",null);product.put("images_provided",false);
                session.insert("com.example.demo.mapper.SupplierIntegrationMapper.upsertSupplierProduct",product);
                product.put("package_qty",1560);product.put("package_unit","g");product.put("base_qty",1560);product.put("base_unit","g");product.put("alt_base_qty",30);product.put("alt_base_unit","EA");
                session.insert("com.example.demo.mapper.SupplierIntegrationMapper.upsertSupplierProduct",product);
                assertThat(number(st,"SELECT base_qty FROM tb_supplier_ingredient_product WHERE supplier_item_code='EGG'")).isEqualByComparingTo("1560");
                assertThat(number(st,"SELECT alt_base_qty FROM tb_supplier_ingredient_product WHERE supplier_item_code='EGG'")).isEqualByComparingTo("30");
                product.put("base_qty",999);product.put("package_qty",999);
                session.insert("com.example.demo.mapper.SupplierIntegrationMapper.upsertSupplierProduct",product);
                assertThat(number(st,"SELECT base_qty FROM tb_supplier_ingredient_product WHERE supplier_item_code='EGG'")).isEqualByComparingTo("1560");
                assertThat(number(st,"SELECT COUNT(*) FROM tb_supplier_ingredient_product WHERE supplier_item_code='EGG' AND ingredient_id IS NULL")).isEqualByComparingTo("1");
                // 기초재고: 금액 갱신 + OPENING 이력, 같은 재고행으로 두 번 등록하면 유니크 키가 막는다
                var value=new HashMap<String,Object>(Map.of("inventory_balance_id",1,"current_base_qty",90,"current_unit","EA","average_unit_cost","196.33","inventory_amount","17669.70","user_id","T"));
                assertThat(session.update("com.example.demo.mapper.InventoryMapper.updateInventoryValue",value)).isEqualTo(1);
                var opening=new HashMap<String,Object>(Map.of("movement_id","OPEN-1","account_id","A","account_ingredient_product_id",1,"location_id","L999","movement_type","OPENING",
                    "quantity_delta",0,"quantity_before",90,"quantity_after",90,"base_unit","EA","reference_type","OPENING"));
                opening.put("reference_id","1");opening.put("unit_cost_snapshot","196.33");opening.put("amount_delta","17669.70");
                opening.put("average_unit_cost_before",0);opening.put("average_unit_cost_after","196.33");opening.put("user_id","T");
                session.insert("com.example.demo.mapper.InventoryMapper.insertValuedMovement",opening);
                assertThat(number(st,"SELECT inventory_amount FROM tb_account_inventory_balance WHERE inventory_balance_id=1")).isEqualByComparingTo("17669.70");
                var again=new HashMap<String,Object>(opening);again.put("movement_id","OPEN-2");
                assertThatThrownBy(()->session.insert("com.example.demo.mapper.InventoryMapper.insertValuedMovement",again)).hasMessageContaining("Duplicate");
                assertThat((Integer)session.selectOne("com.example.demo.mapper.InventoryMapper.hasNonAdjustMovement",Map.of("account_id","A","account_ingredient_product_id",1))).isPositive();
                var listRow=session.<Map<String,Object>>selectList("com.example.demo.mapper.InventoryMapper.AccountInventoryList",criteria).stream()
                    .filter(r->"1".equals(String.valueOf(r.get("account_ingredient_product_id")))).findFirst().orElseThrow();
                assertThat(listRow.get("opening_available")).isEqualTo("N");
            } finally {
                assertThat(schema).matches("test_meal_usage_[0-9a-f]{32}");
                st.execute("DROP DATABASE "+schema);
            }
        }
    }
    private static java.math.BigDecimal number(Statement st,String sql)throws SQLException {try(var r=st.executeQuery(sql)){r.next();return r.getBigDecimal(1);}}
    private static void insert(Connection c,String schema,String table,Map<String,Object> provided)throws SQLException {
        Map<String,Object> values=new LinkedHashMap<>(provided);
        try(var columns=c.getMetaData().getColumns(schema,null,table,null)) {
            while(columns.next()) {
                String name=columns.getString("COLUMN_NAME");
                if(values.containsKey(name)||columns.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls||columns.getString("COLUMN_DEF")!=null||"YES".equals(columns.getString("IS_AUTOINCREMENT"))||"YES".equals(columns.getString("IS_GENERATEDCOLUMN")))continue;
                int type=columns.getInt("DATA_TYPE");values.put(name,switch(type){case Types.DATE->"2026-09-28";case Types.TIMESTAMP->"2026-09-28 00:00:00";case Types.INTEGER,Types.BIGINT,Types.TINYINT,Types.SMALLINT,Types.DECIMAL,Types.NUMERIC->1;default->"X";});
            }
        }
        String sql="INSERT INTO "+table+" ("+String.join(",",values.keySet())+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")";
        try(var statement=c.prepareStatement(sql)){int index=1;for(Object value:values.values())statement.setObject(index++,value);statement.executeUpdate();}
    }
}
