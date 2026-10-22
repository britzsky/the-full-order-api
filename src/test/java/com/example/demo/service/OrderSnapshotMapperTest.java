package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class OrderSnapshotMapperTest {
    @Test void snapshotAndIncomingStatementsParseAndBind() throws Exception {
        Configuration configuration = new Configuration();
        for (String name : new String[]{"MenuMapper", "OrderWorkflowMapper", "ShortageProcurementMapper", "AccountMenuRecipeMapper"}) {
            String resource="mybatis-mapper/"+name+".xml";
            try (InputStream stream=getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(stream,configuration,resource,configuration.getSqlFragments()).parse();
            }
        }
        String insert=configuration.getMappedStatement("com.example.demo.mapper.OrderWorkflowMapper.insertItem")
            .getBoundSql(Map.of()).getSql();
        assertThat(insert).contains("source_ingredient_id", "product_snapshot", "supplier_product_id").doesNotContain("account_ingredient_product_id");
        String incoming=configuration.getMappedStatement("com.example.demo.mapper.ShortageProcurementMapper.incoming")
            .getBoundSql(Map.of()).getSql();
        // 미입고 수량은 발주 품목에 정한 '재고에 넣을 식재료'로만 귀속한다(상품 연결·본사 식자재 분류는 쓰지 않는다).
        assertThat(incoming).contains("i.ingredient_id=oi.source_ingredient_id",
            "tb_account_ingredient_master", "i.account_id=po.account_id").doesNotContain("p.ingredient_id", "mapped_ingredient_id");
        // 원산지는 그 식재료로 가장 최근에 들어온 LOT
        assertThat(configuration.getMappedStatement("com.example.demo.mapper.AccountMenuRecipeMapper.origins")
            .getBoundSql(Map.of("account_id","A")).getSql()).contains("tb_account_inventory_lot", "origin_name_snapshot");
    }
}
