package com.example.demo.service;
import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MealUsageMapperTest {
    @Test void allMealStatementsBindAndDdlCopiesMatch() throws Exception {
        Configuration c=new Configuration();
        for(String name:List.of("MenuMapper","MealPlanV2Mapper","MealUsageMapper","ShortageProcurementMapper","TableMealsMapper")) {
            String resource="mybatis-mapper/"+name+".xml";
            try(InputStream in=getClass().getClassLoader().getResourceAsStream(resource)) { new XMLMapperBuilder(in,c,resource,c.getSqlFragments()).parse(); }
        }
        for(String statement:List.of("lockService","requirements","balances","lots","lockBalance","changeBalance","changeLot","insertUsage","activeUsage","reverseUsage","movement","status","snapshotOrigins","origins"))
            assertThat(c.getMappedStatement("com.example.demo.mapper.MealUsageMapper."+statement).getBoundSql(Map.of("account_id","A","table_id","T")).getSql()).isNotBlank();
        byte[] ddl=Files.readAllBytes(Path.of("ddl-source/order_ddl_local.txt"));
        assertThat(ddl).isEqualTo(Files.readAllBytes(Path.of("ddl-source/the_full_order_schema_fixed_collation_real.sql")));
        String text=new String(ddl,java.nio.charset.StandardCharsets.UTF_8);
        // 재고는 거래처 식재료 단위(2026-10-22): 상품 연결 테이블·컬럼은 만들지 않는다(옛 DB 정리용 DROP만 남는다)
        assertThat(text).contains("CREATE TABLE tb_account_meal_stock_usage", "origin_name_snapshot", "uk_inventory_ingredient_location",
            "tb_favorite_product", "tb_meal_plan_template", "standard_unit_cost", "special_yn")
            .doesNotContain("CREATE TABLE tb_account_ingredient_product", "link_context_snapshot", "account_ingredient_product_id");
    }
}
