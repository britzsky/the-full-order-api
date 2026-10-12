package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.ShortageProcurementMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 실제 로컬 데이터로 마감 알림 계산 확인. 읽기 전용(쓰기 없음, 끝에 롤백). DEADLINE_ACCOUNT_ID로 거래처 지정. */
@EnabledIfEnvironmentVariable(named="RUN_DEADLINE_ALERT_DB",matches="true")
class LocalDeadlineAlertReadOnlyTest {
    @Test void printsAlertsForRealAccount() throws Exception {
        Path root=Path.of("src/main/resources");var props=new Properties();
        try(var reader=Files.newBufferedReader(root.resolve("application.properties"))){props.load(reader);}
        for(String imp:props.getProperty("spring.config.import","").split(",")) {
            var path=root.resolve(imp.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(path.startsWith(root)&&Files.isRegularFile(path))try(var reader=Files.newBufferedReader(path)){props.load(reader);}
        }
        String url=props.getProperty("spring.datasource.url");assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        try(var c=DriverManager.getConnection(url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"))) {
            c.setAutoCommit(false);c.setReadOnly(true);
            var cfg=new Configuration(new Environment("deadline-readonly",new JdbcTransactionFactory(),new SingleConnectionDataSource(c,true)));
            for(String name:List.of("ShortageProcurementMapper","SupplierIntegrationMapper"))
                try(var in=Files.newInputStream(root.resolve("mybatis-mapper/"+name+".xml"))) { new XMLMapperBuilder(in,cfg,name,cfg.getSqlFragments()).parse(); }
            try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession(c)) {
                try {
                    var suppliers=new SupplierIntegrationService(session.getMapper(SupplierIntegrationMapper.class),null);
                    var shortages=new ShortageProcurementService(session.getMapper(ShortageProcurementMapper.class),suppliers,new ObjectMapper());
                    String account=System.getenv().getOrDefault("DEADLINE_ACCOUNT_ID","20250819193526");
                    var result=new DeadlineAlertService(shortages).alerts(new HashMap<>(Map.of("account_id",account,"within_hours","72")));
                    System.out.println("DEADLINE_ALERTS "+new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    var plan=shortages.shortages(new HashMap<>(Map.of("account_id",account,"source","PLAN","date_from",java.time.LocalDate.now().toString(),
                        "date_to",java.time.LocalDate.now().plusDays(14).toString(),"delivery_date",java.time.LocalDate.now().toString())));
                    @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)plan.get("ingredients");
                    System.out.println("PLAN_SHORTAGE_ROWS "+rows.size()+" with_issues="+rows.stream().filter(r->!((Collection<?>)r.get("issues")).isEmpty()).count());
                } finally { c.rollback(); }
            }
        }
    }
}
