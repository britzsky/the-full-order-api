package com.example.demo.service;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 로컬 DB(롤백): 상품·판가가 사업장 단위로 저장된 뒤, 같은 사업장에 새 거래처를 연결하면
 * 공급사를 다시 조회하지 않아도 그 거래처 화면에 같은 상품·판가·준비 상태가 보이는지 확인한다.
 * 실행: RUN_SUPPLIER_SITE_SHARED_DB=true (상품이 받아진 사업장이 하나 이상 있어야 한다)
 */
@EnabledIfEnvironmentVariable(named="RUN_SUPPLIER_SITE_SHARED_DB", matches="true")
class LocalSupplierSiteSharedDatabaseTest {
    @Test void newAccountOnPreparedSiteSeesSameOffersWithoutNewRows() throws Exception {
        var props=new Properties();
        var root=Path.of("src/main/resources");
        try(var reader=Files.newBufferedReader(root.resolve("application.properties"))){props.load(reader);}
        for(String imp:props.getProperty("spring.config.import","").split(",")) {
            var path=root.resolve(imp.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(path.startsWith(root)&&Files.isRegularFile(path))try(var reader=Files.newBufferedReader(path)){props.load(reader);}
        }
        String url=props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        try(var c=DriverManager.getConnection(url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"))) {
            c.setAutoCommit(false);
            try {
                var jdbc=new JdbcTemplate(new SingleConnectionDataSource(c,true));
                // 상품이 가장 많이 받아진 사용 중 사업장과 그 거래처
                var source=jdbc.queryForList("""
                    SELECT ss.account_id,ss.supplier_id,ss.supplier_site_id,ss.external_site_code,COUNT(o.supplier_offer_id) offers
                      FROM the_full_order.tb_supplier_account_site ss
                      JOIN the_full_order.tb_supplier_site_product_offer o ON o.supplier_site_id=ss.supplier_site_id AND o.active_yn='Y'
                     WHERE ss.active_yn='Y' GROUP BY ss.supplier_account_site_id ORDER BY offers DESC LIMIT 1""").get(0);
                long offerRows=jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_supplier_site_product_offer",Long.class);
                long priceRows=jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_supplier_site_product_price",Long.class);
                String shared="SHARE"+UUID.randomUUID().toString().replace("-","").substring(0,10);
                jdbc.update("INSERT INTO the_full_order.tb_supplier_account_site(account_id,supplier_id,supplier_site_id,external_site_code,active_yn) VALUES(?,?,?,?,'Y')",
                    shared,source.get("supplier_id"),source.get("supplier_site_id"),source.get("external_site_code"));

                var cfg=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("site-shared",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),new SingleConnectionDataSource(c,true)));
                try(var input=Files.newInputStream(root.resolve("mybatis-mapper/SupplierIntegrationMapper.xml"))) {
                    new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,cfg,"mybatis-mapper/SupplierIntegrationMapper.xml",cfg.getSqlFragments()).parse();
                }
                // 세션을 닫으면 연결의 자동 커밋이 켜지며 커밋되므로 닫지 않는다(바깥 finally에서 롤백 후 연결만 닫는다)
                var session=new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(cfg).openSession(c);
                {
                    String date=jdbc.queryForObject("SELECT DATE_FORMAT(CURRENT_DATE,'%Y-%m-%d')",String.class);
                    var mine=new HashMap<String,Object>(Map.of("account_id",source.get("account_id"),"delivery_date",date,"external_site_code",source.get("external_site_code")));
                    var theirs=new HashMap<String,Object>(Map.of("account_id",shared,"delivery_date",date,"external_site_code",source.get("external_site_code")));
                    List<Map<String,Object>> a=session.selectList("com.example.demo.mapper.SupplierIntegrationMapper.offers",mine);
                    List<Map<String,Object>> b=session.selectList("com.example.demo.mapper.SupplierIntegrationMapper.offers",theirs);
                    // 새 거래처도 같은 상품(같은 제공 조건 ID)을 본다
                    assertThat(b).isNotEmpty();
                    assertThat(b.stream().map(r->r.get("supplier_offer_id")).toList()).containsExactlyInAnyOrderElementsOf(a.stream().map(r->r.get("supplier_offer_id")).toList());
                    assertThat(b).allSatisfy(r->assertThat(r.get("account_id")).isEqualTo(shared));
                    // 동기화 기록도 사업장 것이 보인다
                    List<Map<String,Object>> mineStatus=session.selectList("com.example.demo.mapper.SupplierIntegrationMapper.syncStatus",Map.of("account_id",source.get("account_id")));
                    List<Map<String,Object>> sharedStatus=session.selectList("com.example.demo.mapper.SupplierIntegrationMapper.syncStatus",Map.of("account_id",shared));
                    assertThat(sharedStatus.stream().map(r->r.get("supplier_sync_checkpoint_id")).toList())
                        .containsAll(mineStatus.stream().filter(r->Objects.equals(r.get("supplier_site_id"),source.get("supplier_site_id"))).map(r->r.get("supplier_sync_checkpoint_id")).toList());
                    // 마감 일수 갱신 SQL: 사업장을 쓰는 거래처 사용상품 대상(실행만 확인)
                    session.update("com.example.demo.mapper.SupplierIntegrationMapper.refreshAccountProductLead",
                        Map.of("supplier_site_id",source.get("supplier_site_id"),"supplier_product_id",-1L,"lead_time_days",1,"user_id","TEST"));
                }
                // 준비 상태(납품일별)도 사업장 기록을 같이 본다
                var service=new CatalogPreparationService(jdbc,org.mockito.Mockito.mock(OurhomeCatalogSyncService.class),org.mockito.Mockito.mock(WelstoryCatalogSyncService.class));
                try {
                    String day=jdbc.queryForObject("SELECT DATE_FORMAT(DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY),'%Y-%m-%d')",String.class);
                    var mineDay=(List<?>)service.status(source.get("account_id").toString(),day).get("suppliers");
                    var sharedDay=(List<?>)service.status(shared,day).get("suppliers");
                    var site=source.get("external_site_code");
                    assertThat(sharedDay).hasSize(1);
                    var sharedRow=(Map<?,?>)sharedDay.get(0);
                    var mineRow=mineDay.stream().map(r->(Map<?,?>)r).filter(r->Objects.equals(r.get("supplier_code"),sharedRow.get("supplier_code"))).findFirst().orElseThrow();
                    assertThat(sharedRow.get("status")).isEqualTo(mineRow.get("status"));
                    System.out.println("SITE_SHARED site="+site+" offers="+source.get("offers")+" status="+sharedDay);
                } finally { service.close(); }
                // 공급사 재조회·저장 없이: 상품 제공 조건·판가 행 수는 그대로
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_supplier_site_product_offer",Long.class)).isEqualTo(offerRows);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_supplier_site_product_price",Long.class)).isEqualTo(priceRows);
            } finally { c.rollback(); }
        }
    }
}
