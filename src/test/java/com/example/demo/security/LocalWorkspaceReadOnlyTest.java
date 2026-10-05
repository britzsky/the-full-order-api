package com.example.demo.security;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import com.example.demo.controller.WorkspaceController;
import com.example.demo.mapper.InventoryMapper;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in; validates new SELECT statements against localhost without writing data. */
@EnabledIfEnvironmentVariable(named="RUN_WORKSPACE_READ_ONLY", matches="true")
class LocalWorkspaceReadOnlyTest {
    @Test void workspaceHistoryAndOwnershipQueriesMatchLocalSchema() throws Exception {
        Path resources=Path.of("src/main/resources");
        Properties props=new Properties();
        for(String file:List.of("application.properties","application-secret.properties","application-local.properties")) {
            try(var reader=Files.newBufferedReader(resources.resolve(file))){props.load(reader);}
        }
        String url=props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        try(var connection=DriverManager.getConnection(url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"))) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            var ds=new SingleConnectionDataSource(connection,true);
            var jdbc=new JdbcTemplate(ds);
            var access=new WorkspaceAccess(jdbc);
            var request=new MockHttpServletRequest();
            var session=new MockHttpSession();
            session.setAttribute(WorkspaceUser.SESSION_KEY,new WorkspaceUser("readonly-test","2",""));
            request.setSession(session);
            var tasks=new WorkspaceController(jdbc,access).tasks("__READONLY_NONEXISTENT__",request);
            assertThat(tasks).containsEntry("role","HEADQUARTERS");
            assertThat((List<?>)tasks.get("accounts")).isEmpty();
            var site=new WorkspaceUser("readonly-test","3","__READONLY_NONEXISTENT__");
            for(String key:List.of("inventory_balance_id","account_ingredient_product_id","account_recipe_detail_id","meal_service_id","purchase_order_id","table_id","supplier_account_site_id","procurement_cart_id","meal_detail_id","supplier_offer_id","procurement_cart_item_id","purchase_order_item_id"))
                access.checkResource(site,key,"0");
            var cfg=new Configuration(new Environment("workspace-read-only",new JdbcTransactionFactory(),ds));
            String resource="mybatis-mapper/InventoryMapper.xml";
            try(var stream=Files.newInputStream(resources.resolve(resource))){new XMLMapperBuilder(stream,cfg,resource,cfg.getSqlFragments()).parse();}
            try(var sql=new SqlSessionFactoryBuilder().build(cfg).openSession(connection)) {
                assertThat(sql.getMapper(InventoryMapper.class).movements(Map.of("account_id","__READONLY_NONEXISTENT__","date_from","2026-10-01","date_to","2026-10-01","limit",50,"offset",0))).isEmpty();
                sql.rollback();
            }
        }
    }
}
