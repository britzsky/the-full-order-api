package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ddl-source 테스트 데이터(2026-10-07 보강분)로 빨강/노랑 부족, 발주 마감 알림, 기초재고 추정 단가, 직접구매 재고가
 * 실제 서비스 계산에 나오는지 확인. 시드를 한 트랜잭션 안에서 실행하고 항상 롤백한다(로컬 DB 불변).
 */
@EnabledIfEnvironmentVariable(named="RUN_SEED_FEATURES_DB",matches="true")
class LocalSeedFeaturesDatabaseTest {
    static final String ACCOUNT="20250819193455";

    @SuppressWarnings("unchecked")
    @Test void seededDataShowsNewFeatures() throws Exception {
        Path root=Path.of("src/main/resources");var props=new Properties();
        try(var reader=Files.newBufferedReader(root.resolve("application.properties"))){props.load(reader);}
        for(String imp:props.getProperty("spring.config.import","").split(",")) {
            var path=root.resolve(imp.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(path.startsWith(root)&&Files.isRegularFile(path))try(var reader=Files.newBufferedReader(path)){props.load(reader);}
        }
        String url=props.getProperty("spring.datasource.url");assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        try(var c=DriverManager.getConnection(url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"))) {
            c.setAutoCommit(false);
            try {
                ScriptUtils.executeSqlScript(c,new EncodedResource(new FileSystemResource("ddl-source/the_full_order_seed_testdata_fixed_utf8mb4_real.sql"),"UTF-8"));
                var cfg=new Configuration(new Environment("seed-rollback",new JdbcTransactionFactory(),new SingleConnectionDataSource(c,true)));
                for(String name:List.of("MenuMapper","InventoryMapper","ShortageProcurementMapper","SupplierIntegrationMapper"))
                    try(var in=Files.newInputStream(root.resolve("mybatis-mapper/"+name+".xml"))) { new XMLMapperBuilder(in,cfg,"mybatis-mapper/"+name+".xml",cfg.getSqlFragments()).parse(); }
                // 세션을 닫으면 연결도 닫혀 롤백할 수 없으므로 닫지 않는다(연결은 바깥 try가 닫음).
                var session=new SqlSessionFactoryBuilder().build(cfg).openSession(c);
                {
                    var shortages=new ShortageProcurementService(session.getMapper(ShortageProcurementMapper.class),
                        new SupplierIntegrationService(session.getMapper(SupplierIntegrationMapper.class),null),new ObjectMapper());
                    var today=java.time.LocalDate.now();
                    var plan=shortages.shortages(new HashMap<>(Map.of("account_id",ACCOUNT,"source","PLAN","date_from",today.toString(),
                        "date_to",today.plusDays(6).toString(),"delivery_date",today.plusDays(1).toString())));
                    var rows=(List<Map<String,Object>>)plan.get("ingredients");
                    Map<Object,Long> status=new TreeMap<>();
                    rows.forEach(r->status.merge(r.get("stock_status"),1L,Long::sum));
                    Map<Object,Long> issues=new TreeMap<>();
                    rows.forEach(r->((Collection<?>)r.get("issues")).forEach(i->issues.merge(i,1L,Long::sum)));
                    System.out.println("SEED_PLAN_ISSUES "+issues);
                    System.out.println("SEED_PLAN_STATUS "+status+" rows="+rows.size()+" with_issues="+rows.stream().filter(r->!((Collection<?>)r.get("issues")).isEmpty()).count());
                    assertThat(status).containsKey("RED");

                    var alerts=(List<Map<String,Object>>)new DeadlineAlertService(shortages).alerts(new HashMap<>(Map.of("account_id",ACCOUNT,"within_hours","72"))).get("alerts");
                    alerts.forEach(a->System.out.println("SEED_ALERT "+a.get("supplier_name")+" "+a.get("deadline")+" 납품 "+a.get("delivery_date")+" "+a.get("rule")+" items="+((List<?>)a.get("items")).size()));
                    assertThat(alerts).isNotEmpty();

                    var inventory=new InventoryService(session.getMapper(InventoryMapper.class),org.mockito.Mockito.mock(StockAvailabilityService.class));
                    var stock=inventory.AccountInventoryList(new HashMap<>(Map.of("account_id",ACCOUNT)));
                    var unregistered=stock.stream().filter(r->"TEST-A-003".equals(r.get("supplier_item_code"))).findFirst().orElseThrow();
                    System.out.println("SEED_OPENING_ESTIMATE "+unregistered.get("estimated_unit_cost")+" "+unregistered.get("estimated_cost_source")+" "+unregistered.get("estimated_cost_date"));
                    assertThat(unregistered.get("estimated_unit_cost")).isNotNull();
                    assertThat(stock).anySatisfy(r->{assertThat(r.get("supplier_item_code")).isEqualTo("TEST-A-002");assertThat(r.get("opening_cost_estimated")).isEqualTo("Y");});
                    assertThat(stock).anySatisfy(r->{assertThat(r.get("supplier_code")).isEqualTo("DIRECT");assertThat(r.get("product_name")).isEqualTo("양파 (마트)");});
                }
            } finally { c.rollback(); }
        }
    }
}
