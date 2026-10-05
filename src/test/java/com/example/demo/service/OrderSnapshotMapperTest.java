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
        assertThat(insert).contains("source_ingredient_id", "product_snapshot", "link_context_snapshot");
        String incoming=configuration.getMappedStatement("com.example.demo.mapper.ShortageProcurementMapper.incoming")
            .getBoundSql(Map.of()).getSql();
        // 미입고 수량은 발주 원본 식자재 → 거래처 연결 순으로만 귀속한다. 본사 식자재 분류(p.ingredient_id)는 쓰지 않는다.
        assertThat(incoming).contains("COALESCE(NULLIF(oi.source_ingredient_id,''),ap.mapped_ingredient_id)",
            "tb_account_ingredient_master", "i.account_id=po.account_id").doesNotContain("p.ingredient_id");
        assertThat(configuration.getMappedStatement("com.example.demo.mapper.AccountMenuRecipeMapper.origins")
            .getBoundSql(Map.of("account_id","A")).getSql()).contains("ss.account_id=r.account_id", "provider_attributes");
    }
}
