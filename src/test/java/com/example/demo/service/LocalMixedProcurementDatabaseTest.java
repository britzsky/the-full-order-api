package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.Reader;
import java.nio.file.*;
import java.util.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.demo.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** localhost만 허용. 업무 데이터는 롤백한다. 새 테이블 적용은 별도 명시적 opt-in이다. */
@EnabledIfEnvironmentVariable(named="RUN_LOCAL_MIXED_DB_TESTS",matches="true")
class LocalMixedProcurementDatabaseTest {
    @Test void syncDatePricesAndBatchManifestUseActualSqlAndRollback() throws Exception {
        Path resources=Path.of("src/main/resources");Properties props=new Properties();
        try(Reader r=Files.newBufferedReader(resources.resolve("application.properties"))){props.load(r);}
        for(String imported:props.getProperty("spring.config.import","").split(",")){
            Path p=resources.resolve(imported.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(p.startsWith(resources)&&Files.isRegularFile(p))try(Reader r=Files.newBufferedReader(p)){props.load(r);}
        }
        String url=props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        var source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"));
        if("true".equals(System.getenv("APPLY_LOCAL_MIXED_SCHEMA"))){
            String ddl=Files.readString(Path.of("ddl-source/order_ddl_local.txt"));
            int start=ddl.indexOf("CREATE TABLE IF NOT EXISTS the_full_order.tb_procurement_batch (");
            assertThat(start).isGreaterThan(0);
            String create=ddl.substring(start,ddl.indexOf(';',start)+1);
            try(var c=source.getConnection();var statement=c.createStatement()){statement.execute(create);}
        }
        var config=new Configuration(new Environment("local-mixed",new SpringManagedTransactionFactory(),source));
        for(String name:List.of("SupplierIntegrationMapper","OrderWorkflowMapper","SupplierSiteMapper")){
            String resource="mybatis-mapper/"+name+".xml";
            try(var stream=Files.newInputStream(resources.resolve(resource))){new XMLMapperBuilder(stream,config,resource,config.getSqlFragments()).parse();}
        }
        config.addMapper(ProcurementBatchMapper.class);
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        var mapper=session.getMapper(SupplierIntegrationMapper.class);
        var batch=session.getMapper(ProcurementBatchMapper.class);
        var manager=new DataSourceTransactionManager(source);
        var api=mock(OurhomeSiteService.class);var json=new ObjectMapper();
        String site="MIXED"+UUID.randomUUID().toString().substring(0,8), code="MIXED"+UUID.randomUUID();
        when(api.requireSite(site)).thenReturn(new OurhomeSiteService.Site(site,"롤백 검증","H","","","","","",List.of("2099-10-01","2099-10-02")));
        when(api.catalogPage(any(),anyString(),anyString())).thenAnswer(i->{String date=i.getArgument(1);
            return new OurhomeSiteService.ProductResult("",site,date,1,false,"",List.of(Map.of("goodcd",code,"goodnm","롤백상품","odrUnit","KG","goodsz","KG","goodStatus","Q","odrupYnDesc","0.5","decOdrupYn","Y","taxYn","N","salsUcost",date.endsWith("01")?"100":"200")));});
        var siteService=new SupplierSiteService(session.getMapper(SupplierSiteMapper.class),mapper);
        var sync=new OurhomeCatalogSyncService(api,mapper,json,manager,siteService);
        // 실제 거래처의 공급사 매핑을 건드리지 않도록 롤백되는 임시 거래처를 쓴다.
        String account="MIXED-ACC-"+UUID.randomUUID().toString().substring(0,8);
        new TransactionTemplate(manager).executeWithoutResult(status->{
            status.setRollbackOnly();
            for(String date:List.of("2099-10-01","2099-10-02"))sync.sync(Map.of("account_id",account,"siteCode",site,"deliveryDate",date));
            for(String date:List.of("2099-10-01","2099-10-02")){
                var rows=mapper.offers(Map.of("account_id",account,"supplier_code","OURHOME","external_site_code",site,"delivery_date",date,"price_at",date));
                assertThat(rows).hasSize(1);assertThat(rows.get(0).get("purchase_price").toString()).isEqualTo(date.endsWith("01")?"100.00":"200.00");
                assertThat(rows.get(0).get("base_qty").toString()).isEqualTo("1000.000");
                assertThat(rows.get(0).get("ingredient_id")).isNull();
            }
            // 거래처당 아워홈 사용 중 사업장은 1개: 서비스가 다른 사업장 동기화를 막고, DB 제약도 직접 막는다.
            when(api.requireSite(site+"B")).thenReturn(new OurhomeSiteService.Site(site+"B","다른 사업장","H","","","","","",List.of("2099-10-01")));
            assertThatThrownBy(()->sync.sync(Map.of("account_id",account,"siteCode",site+"B","deliveryDate","2099-10-01"))).hasMessageContaining("사업장 매핑");
            Long ourhomeId=siteService.supplierId("OURHOME",null);
            siteService.saveProviderSite(ourhomeId,new HashMap<>(Map.of("external_site_code",site+"B","external_site_name","다른 사업장")),null);
            assertThatThrownBy(()->session.getMapper(SupplierSiteMapper.class).insertMapping(new HashMap<>(Map.of("account_id",account,"supplier_id",ourhomeId,"external_site_code",site+"B"))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            Map<String,Object> p=new HashMap<>(Map.of("account_id",account,"request_key",UUID.randomUUID().toString(),"request_fingerprint","a".repeat(64),"group_payload","[]"));
            batch.reserve(p);p.put("request_fingerprint","b".repeat(64));batch.reserve(p);
            assertThat(batch.find(p).get("request_fingerprint")).isEqualTo("a".repeat(64));
            when(api.catalogPage(any(),anyString(),anyString())).thenReturn(new OurhomeSiteService.ProductResult("",site,"2099-10-01",0,false,"",List.of()));
            sync.sync(Map.of("account_id",account,"siteCode",site,"deliveryDate","2099-10-01"));
            var rows=mapper.offers(Map.of("account_id",account,"supplier_code","OURHOME","external_site_code",site,"delivery_date","2099-10-01","price_at","2099-10-01"));
            assertThat(rows.get(0).get("orderable_yn")).isEqualTo("N");
            assertThat(mapper.offers(Map.of("account_id","OTHER-ACCOUNT","supplier_offer_id",rows.get(0).get("supplier_offer_id"),"delivery_date","2099-10-01","price_at","2099-10-01"))).isEmpty();
        });
        verify(api,never()).submitOrder(any());
    }
}
