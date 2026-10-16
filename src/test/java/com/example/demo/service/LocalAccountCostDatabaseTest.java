package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.math.BigDecimal;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.MenuMapper;

/** Dedicated synthetic rows only; always rolled back. Never updates business rows or schema. */
@EnabledIfEnvironmentVariable(named="RUN_ACCOUNT_COST_DB",matches="true")
class LocalAccountCostDatabaseTest {
    @Test void planningCostFollowsAutomaticPriorityWithManualOverride() throws Exception {
        Path root=Path.of("src/main/resources");var props=new Properties();
        try(var reader=Files.newBufferedReader(root.resolve("application.properties"))){props.load(reader);}
        for(String imp:props.getProperty("spring.config.import","").split(",")) {
            var path=root.resolve(imp.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(path.startsWith(root)&&Files.isRegularFile(path))try(var reader=Files.newBufferedReader(path)){props.load(reader);}
        }
        String url=props.getProperty("spring.datasource.url");assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        try(var c=DriverManager.getConnection(url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"))) {
            try(var s=c.createStatement();var r=s.executeQuery("SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema='the_full_order' AND table_name='tb_account_planning_cost' AND column_name='unit_cost'")) {
                assertThat(r.next()).as("tb_account_planning_cost 없음 — ddl-source 정의로 먼저 생성").isTrue();
                assertThat(r.getString(1)).as("tb_account_planning_cost.unit_cost는 NULL 허용이어야 함(AUTO 행)").isEqualTo("YES");
            }
            c.setAutoCommit(false);
            var cfg=new Configuration(new Environment("cost-rollback",new JdbcTransactionFactory(),new SingleConnectionDataSource(c,true)));
            for(String name:List.of("MenuMapper","MealPlanV2Mapper"))try(var in=Files.newInputStream(root.resolve("mybatis-mapper/"+name+".xml"))) {
                new XMLMapperBuilder(in,cfg,"mybatis-mapper/"+name+".xml",cfg.getSqlFragments()).parse();
            }
            try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession(c)) {
                try {
                    String id="COST"+UUID.randomUUID().toString().replace("-","").substring(0,12);
                    long supplier=write(c,"INSERT INTO tb_supplier(supplier_code,supplier_name,supplier_type) VALUES(?,?,'FOOD')",id,id);
                    write(c,"INSERT INTO tb_ingredient_master(ingredient_id,ingredient_name_raw,ingredient_name_std,base_unit) VALUES(?,?,?,'g')",id,id,id);
                    write(c,"INSERT INTO tb_account_ingredient_master(account_id,ingredient_id,base_unit) VALUES(?,?,'g')",id,id);
                    write(c,"INSERT INTO tb_menu_master(menu_id,menu_name,food_type) VALUES(?,?,1)",id,id);
                    write(c,"INSERT INTO tb_account_menu_master(account_id,menu_id,menu_name) VALUES(?,?,?)",id,id,id);
                    write(c,"INSERT INTO tb_account_recipe_detail(account_id,recipe_id,menu_id,ingredient_id,qty_num,qty_unit,qty_base,base_unit,qty_per_person) VALUES(?,1,?,?,180,'g',180,'g',180)",id,id,id);
                    long product=write(c,"INSERT INTO tb_supplier_ingredient_product(supplier_id,supplier_item_code,product_name,order_unit,package_unit,base_qty,base_unit) VALUES(?,?,?,'KG','g',1000,'g')",supplier,id,id);
                    check(session,id,"2026-10-06",null,null,"공급상품 연결 필요");
                    long link=write(c,"INSERT INTO tb_account_ingredient_product(account_id,supplier_product_id,mapped_ingredient_id,preferred_yn) VALUES(?,?,?,'Y')",id,product,id);
                    check(session,id,"2026-10-06",null,null,"가격 정보 없음");
                    long site=write(c,"INSERT INTO tb_supplier_site(supplier_id,external_site_code) VALUES(?,?)",supplier,id);
                    write(c,"INSERT INTO tb_supplier_account_site(account_id,supplier_id,supplier_site_id,external_site_code) VALUES(?,?,?,?)",id,supplier,site,id);
                    long offer=write(c,"INSERT INTO tb_supplier_site_product_offer(supplier_site_id,supplier_product_id) VALUES(?,?)",site,product);
                    write(c,"INSERT INTO tb_supplier_site_product_price(supplier_offer_id,purchase_price,effective_from,effective_to) VALUES(?,6000,'2026-10-06','2026-10-07'),(?,12000,'2026-10-07','2026-10-08')",offer,offer);
                    write(c,"INSERT INTO tb_supplier_ingredient_price_history(supplier_product_id,purchase_price,effective_from,effective_to) VALUES(?,9000,'2026-10-05','2026-10-06')",product);
                    long otherSite=write(c,"INSERT INTO tb_supplier_site(supplier_id,external_site_code) VALUES(?,?)",supplier,id+"OTHER");
                    write(c,"INSERT INTO tb_supplier_account_site(account_id,supplier_id,supplier_site_id,external_site_code) VALUES(?,?,?,?)",id+"OTHER",supplier,otherSite,id+"OTHER");
                    long otherOffer=write(c,"INSERT INTO tb_supplier_site_product_offer(supplier_site_id,supplier_product_id) VALUES(?,?)",otherSite,product);
                    write(c,"INSERT INTO tb_supplier_site_product_price(supplier_offer_id,purchase_price,effective_from,effective_to) VALUES(?,1,'2026-10-01','2026-11-01')",otherOffer);
                    check(session,id,"2026-10-06","1080","SITE_PRICE",null);        // 기준일 유효 거래처 판가, 타 거래처 가격 무시
                    check(session,id,"2026-10-07","2160","SITE_PRICE",null);
                    check(session,id,"2026-10-05","1620","COMMON_PRICE",null);      // 거래처 판가 없으면 공통 판가
                    check(session,id,"2026-10-20","2160","SITE_PRICE_NEAREST",null);// 유효 판가 없으면 가장 가까운 날짜
                    var plan=new HashMap<String,Object>();
                    plan.put("table_id",id);plan.put("account_id",id);plan.put("table_name",id);plan.put("table_year",2026);plan.put("table_month",10);plan.put("table_week",1);
                    plan.put("meal_date","2026-10-07");plan.put("meal_slot","lunch");plan.put("meal_slot_code",2);plan.put("planned_servings",1);
                    plan.put("meal_budget_per_person",99999);plan.put("menu_id",id);plan.put("menu_name",id);plan.put("cost_date","2026-10-06");
                    plan.put("budget_source","DIET_PRICE");plan.put("budget_source_amount",99999);plan.put("budget_divisor",1);
                    session.insert("com.example.demo.mapper.MealPlanV2Mapper.insertPlan",plan);
                    session.insert("com.example.demo.mapper.MealPlanV2Mapper.insertService",plan);
                    session.insert("com.example.demo.mapper.MealPlanV2Mapper.insertDetail",plan);
                    Map<String,Object> validation=session.selectOne("com.example.demo.mapper.MealPlanV2Mapper.selectServiceCostValidation",plan);
                    assertThat(new BigDecimal(validation.get("meal_cost_per_person").toString())).isEqualByComparingTo("2160"); // 저장 식사 날짜(10/7) 기준
                    session.insert("com.example.demo.mapper.MealPlanV2Mapper.insertRequirements",plan);
                    Map<String,Object> requirement=session.selectOne("com.example.demo.mapper.MealPlanV2Mapper.requirements",plan);
                    assertThat(new BigDecimal(requirement.get("calculated_cost").toString())).isEqualByComparingTo("2160");

                    write(c,"DELETE FROM tb_supplier_site_product_price WHERE supplier_offer_id IN (?,?)",offer,otherOffer);
                    write(c,"DELETE FROM tb_supplier_ingredient_price_history WHERE supplier_product_id=?",product);
                    write(c,"INSERT INTO tb_account_inventory_lot(account_id,account_ingredient_product_id,received_date,received_base_qty,remaining_base_qty,base_unit,receipt_unit_cost) VALUES(?,?,'2026-09-01',1,0,'kg',5000),(?,?,'2026-09-20',1000,500,'g',7)",id,link,id,link);
                    check(session,id,"2026-10-06","1260","RECENT_RECEIPT",null);    // 판가 없으면 최근 입고 단가
                    write(c,"DELETE FROM tb_account_inventory_lot WHERE account_id=?",id);
                    write(c,"INSERT INTO tb_account_inventory_balance(account_id,account_ingredient_product_id,location_id,current_base_qty,base_unit,average_unit_cost) VALUES(?,?,'COST_KG',1,'kg',8000),(?,?,'COST_G',1000,'g',10)",id,link,id,link);
                    check(session,id,"2026-10-06","1620","INVENTORY_AVERAGE",null); // kg/g 가중 평균 (8000+10000)/2000g

                    var costs=new PlanningCostService(new org.springframework.jdbc.core.JdbcTemplate(new SingleConnectionDataSource(c,true)),session.getMapper(MenuMapper.class));
                    var preview=costs.preview(id,id,product);
                    assertThat(preview.get("manual")).isNull();
                    assertThat(((Map<?,?>)preview.get("auto")).get("source_type")).isEqualTo("INVENTORY_AVERAGE");
                    var manual=new HashMap<String,Object>();
                    manual.put("account_id",id);manual.put("ingredient_id",id);manual.put("supplier_product_id",product);
                    manual.put("source_type","MANUAL");manual.put("unit_cost","10");manual.put("base_unit","g");manual.put("expected_version",preview.get("expected_version"));
                    costs.confirm(manual);
                    check(session,id,"2026-10-06","1800","MANUAL",null);
                    assertThatThrownBy(()->costs.confirm(manual)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class); // 오래된 버전
                    var wrongUnit=new HashMap<>(manual);wrongUnit.put("base_unit","ml");wrongUnit.remove("expected_version");
                    assertThatThrownBy(()->costs.confirm(wrongUnit)).isInstanceOf(IllegalArgumentException.class);
                    var wrongAccount=new HashMap<>(manual);wrongAccount.put("account_id",id+"OTHER");wrongAccount.remove("expected_version");
                    assertThatThrownBy(()->costs.confirm(wrongAccount)).isInstanceOf(IllegalArgumentException.class);
                    var revert=new HashMap<String,Object>(manual);revert.put("source_type","AUTO");revert.put("expected_version",costs.preview(id,id,product).get("expected_version"));
                    costs.confirm(revert);
                    check(session,id,"2026-10-06","1620","INVENTORY_AVERAGE",null);
                    assertThat(((List<?>)costs.preview(id,id,product).get("history"))).hasSize(2);

                    requirement=session.selectOne("com.example.demo.mapper.MealPlanV2Mapper.requirements",plan);
                    assertThat(new BigDecimal(requirement.get("calculated_cost").toString())).isEqualByComparingTo("2160"); // 저장 식단 스냅샷 유지
                    write(c,"UPDATE tb_account_recipe_detail SET review_flag=1 WHERE account_id=?",id);
                    check(session,id,"2026-10-06",null,"INVENTORY_AVERAGE","단위 확인");
                } finally { session.rollback(); }
            }
        }
    }
    private static long write(Connection c,String sql,Object...args)throws Exception {
        try(var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();
            try(var keys=s.getGeneratedKeys()){return keys.next()?keys.getLong(1):0;}
        }
    }
    private static void check(SqlSession session,String id,String date,String expected,String source,String reason) {
        session.clearCache();var p=Map.<String,Object>of("account_id",id,"menu_id",id,"cost_date",date,"servingQty",1);
        Map<String,Object> detail=session.selectOne("com.example.demo.mapper.MenuMapper.AccountDetailList",p);
        Map<String,Object> menu=session.selectOne("com.example.demo.mapper.MenuMapper.AccountMenuList",p);
        assertThat(detail.get("cost_source")).as(date+" source").isEqualTo(source);
        if(reason!=null) assertThat(detail.get("cost_reason")).as(date+" reason").isEqualTo(reason);
        if(expected==null) {assertThat(detail.get("cost_per_person")).isNull();assertThat(menu.get("menu_cost_per_person")).isNull();}
        else {
            assertThat(new BigDecimal(detail.get("cost_per_person").toString())).as(date+" detail").isEqualByComparingTo(expected);
            assertThat(new BigDecimal(menu.get("menu_cost_per_person").toString())).as(date+" menu").isEqualByComparingTo(expected);
        }
    }
}
