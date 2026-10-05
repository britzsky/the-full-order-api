package com.example.demo.security;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import com.example.demo.mapper.ShortageProcurementMapper;
import com.example.demo.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Localhost only. Adds the new table if absent; all synthetic notification data rolls back. */
@EnabledIfEnvironmentVariable(named="RUN_WORKSPACE_AUTOMATION_LOCAL",matches="true")
class LocalWorkspaceAutomationTest {
    @Test void newQueriesAndNotificationLifecycleUseRealLocalSqlWithoutBusinessWrites() throws Exception {
        var resources=Path.of("src/main/resources");var props=new Properties();
        for(String file:List.of("application.properties","application-secret.properties","application-local.properties"))
            try(var reader=Files.newBufferedReader(resources.resolve(file))){props.load(reader);}
        String url=props.getProperty("spring.datasource.url");assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        try(var connection=DriverManager.getConnection(url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"))) {
            var ds=new SingleConnectionDataSource(connection,true);var jdbc=new JdbcTemplate(ds);
            String ddl=Files.readString(Path.of("docs/WORKSPACE_NOTIFICATION_DB_UPDATE.sql"));assertThat(ddl).doesNotContain("DROP TABLE");
            jdbc.execute(ddl);
            // Remove only synthetic rows from this opt-in test's interrupted prior runs.
            for(var row:jdbc.queryForList("SELECT DISTINCT account_id FROM the_full_order.tb_workspace_notification WHERE LEFT(account_id,18)=?","__AUTOMATION_TEST_"))
                jdbc.update("DELETE FROM the_full_order.tb_workspace_notification WHERE account_id=?",row.get("account_id"));
            connection.setAutoCommit(false);
            try {
                String account="__AUTOMATION_TEST_"+UUID.randomUUID().toString().substring(0,8);
                var notificationJdbc=new JdbcTemplate(ds) {
                    @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
                        if(sql.startsWith("SELECT account_id FROM the_full.tb_account")) return List.of(Map.of("account_id",account));
                        return super.queryForList(sql,args);
                    }
                };
                var shortages=mock(ShortageProcurementService.class);
                when(shortages.shortages(anyMap())).thenReturn(Map.of("ingredients",List.of(Map.of("ingredient_id","I","ingredient_name","시험 감자","needed_by","2026-10-03","shortage_qty",100,"base_unit","g","issues",List.of()))));
                var notices=new WorkspaceNotificationService(notificationJdbc,shortages);
                var rows=notices.refresh(account);assertThat(rows).hasSize(1);var task=rows.get(0);
                notices.state(account,task.get("event_key").toString(),task.get("fingerprint").toString(),"IN_PROGRESS","worker");
                assertThat(notices.refresh(account).get(0).get("status")).isEqualTo("IN_PROGRESS");
                assertThatThrownBy(()->notices.state(account,task.get("event_key").toString(),"stale","IN_PROGRESS","worker")).hasMessageContaining("변경");
                when(shortages.shortages(anyMap())).thenReturn(Map.of("ingredients",List.of()));
                assertThat(notices.refresh(account)).isEmpty();
                notices.mealChanged(account,"PLAN","worker");var event=notices.list(account).get(0);
                notices.state(account,event.get("event_key").toString(),event.get("fingerprint").toString(),"RESOLVED","worker");
                assertThat(notices.list(account)).isEmpty();notices.mealChanged(account,"PLAN","worker");assertThat(notices.list(account)).hasSize(1);
                var prepare=new CatalogPreparationService(jdbc,mock(OurhomeCatalogSyncService.class),mock(WelstoryCatalogSyncService.class));
                try { assertThat((List<?>)prepare.status(account,LocalDate.now(ZoneId.of("Asia/Seoul")).toString()).get("suppliers")).isEmpty(); }
                finally { prepare.close(); }
                var factory=new JdbcTransactionFactory() {
                    @Override public org.apache.ibatis.transaction.Transaction newTransaction(Connection c) {
                        return new org.apache.ibatis.transaction.jdbc.JdbcTransaction(c) {
                            @Override public void close() { /* Test owns rollback and connection lifecycle. */ }
                        };
                    }
                };
                var cfg=new Configuration(new Environment("automation-local",factory,ds));
                String resource="mybatis-mapper/ShortageProcurementMapper.xml";
                try(var stream=Files.newInputStream(resources.resolve(resource))){new XMLMapperBuilder(stream,cfg,resource,cfg.getSqlFragments()).parse();}
                try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession(ds.getConnection())) {
                    var mapper=session.getMapper(ShortageProcurementMapper.class);
                    var demand=new ShortageProcurementService(mapper,mock(SupplierIntegrationService.class),new ObjectMapper());
                    var p=demand.criteria(new HashMap<>(Map.of("account_id",account,"source","DRAFT","table_id","TEST","draft_meals",List.of(Map.of("menu_id","NONE","planned_servings",1)))));
                    assertThat(mapper.stock(p)).isEmpty();assertThat(mapper.targets(p)).isEmpty();assertThat(mapper.incoming(p)).isEmpty();assertThat(mapper.demand(p)).isEmpty();assertThat(mapper.history(p)).isEmpty();assertThat(mapper.draftDemand(p)).hasSize(1);
                    var fakeMapper=mock(ShortageProcurementMapper.class);
                    var impact=new MealPlanImpactService(demand,fakeMapper,jdbc).compare(p);
                    assertThat((List<?>)impact.get("affected_orders")).isEmpty();
                }
            } finally { connection.rollback(); }
        }
    }
}
