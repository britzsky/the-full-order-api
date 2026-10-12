package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.Reader;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.example.demo.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 거래처 사용상품 행이 없는 아워홈 상품도 상품 목록 → 발주(접수 시 미연결 식재료 자동 연결) → 입고까지 진행되는지
 * 실제 SQL로 확인한다. 외부 API는 모의 응답, 모든 변경은 마지막에 롤백한다(서비스 내부 커밋도 막는다).
 */
@EnabledIfEnvironmentVariable(named="RUN_LOCAL_DB_TESTS", matches="true")
class LocalUnusedProductOrderDatabaseTest {
    static final String ACCOUNT=System.getenv().getOrDefault("TEST_ACCOUNT","20250819193526");
    static final String SITE=System.getenv().getOrDefault("TEST_OURHOME_SITE","FU0UR");
    static final String DATE=System.getenv().getOrDefault("TEST_DELIVERY_DATE","2026-10-09");

    @Test void productWithoutAccountRowCanBeOrderedReceivedAndAutoLinked() throws Exception {
        Path resources=Path.of("src/main/resources");Properties props=new Properties();
        try(Reader r=Files.newBufferedReader(resources.resolve("application.properties"))){props.load(r);}
        for(String imported:props.getProperty("spring.config.import","").split(",")){
            Path p=resources.resolve(imported.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(p.startsWith(resources)&&Files.isRegularFile(p))try(Reader r=Files.newBufferedReader(p)){props.load(r);}
        }
        String url=props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        Connection real=DriverManager.getConnection(url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"));
        real.setAutoCommit(false);
        // commit·autocommit·close를 무시해 서비스의 REQUIRES_NEW 커밋도 마지막 롤백에 포함시킨다.
        Connection guarded=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->switch(method.getName()){
            case "commit","close" -> null;
            case "setAutoCommit" -> null;
            case "getAutoCommit" -> false;
            default -> method.invoke(real,args);
        });
        String productId=null;
        try {
            var ds=new SingleConnectionDataSource(guarded,true);
            var manager=new DataSourceTransactionManager(ds);
            var configuration=new Configuration(new Environment("rollback",new SpringManagedTransactionFactory(),ds));
            configuration.setCallSettersOnNulls(true);
            for(String name:List.of("OrderWorkflowMapper","SupplierIntegrationMapper")){
                String resource="mybatis-mapper/"+name+".xml";
                try(var stream=Files.newInputStream(resources.resolve(resource))){new XMLMapperBuilder(stream,configuration,resource,configuration.getSqlFragments()).parse();}
            }
            var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
            var catalog=session.getMapper(SupplierIntegrationMapper.class);var orders=session.getMapper(OrderWorkflowMapper.class);

            // 1) 이 거래처에 사용상품 행이 없는 발주 가능 아워홈 상품을 고른다.
            Map<String,Object> offer=null;
            try(var st=guarded.prepareStatement("""
                    SELECT p.supplier_product_id,p.supplier_item_code FROM the_full_order.tb_supplier_ingredient_product p
                    JOIN the_full_order.tb_supplier s ON s.supplier_id=p.supplier_id AND s.supplier_code='OURHOME'
                    WHERE p.base_qty>0 AND LOWER(p.base_unit) IN ('g','kg')
                      AND NOT EXISTS(SELECT 1 FROM the_full_order.tb_account_ingredient_product ap WHERE ap.account_id=? AND ap.supplier_product_id=p.supplier_product_id)
                    LIMIT 200""")){
                st.setString(1,ACCOUNT);
                try(var rs=st.executeQuery()){
                    while(offer==null && rs.next()){
                        var rows=catalog.offers(query(rs.getString(2)));
                        if(rows.size()!=1) continue;
                        var row=new HashMap<>(rows.get(0));
                        if("N".equals(row.get("orderable_yn")) || new BigDecimal(String.valueOf(row.get("purchase_price"))).signum()<=0) continue;
                        try { OrderAvailability.requireAvailable(row,LocalDate.parse(DATE),true); } catch(RuntimeException e) { continue; }
                        offer=row; productId=rs.getString(1);
                    }
                }
            }
            assertThat(offer).as("발주 가능한 미사용 아워홈 상품 필요(%s %s %s)",ACCOUNT,SITE,DATE).isNotNull();
            assertThat(accountRow(guarded,productId)).isNull();
            String ingredient;
            try(var st=guarded.prepareStatement("""
                    SELECT im.ingredient_id FROM the_full_order.tb_account_ingredient_master im
                    WHERE im.account_id=? AND LOWER(im.base_unit)='g'
                      AND NOT EXISTS(SELECT 1 FROM the_full_order.tb_account_ingredient_product ap WHERE ap.account_id=im.account_id AND ap.mapped_ingredient_id=im.ingredient_id)
                    LIMIT 1""")){
                st.setString(1,ACCOUNT);
                try(var rs=st.executeQuery()){assertThat(rs.next()).as("미연결 g 단위 거래처 식재료 필요").isTrue();ingredient=rs.getString(1);}
            }

            // 2) 화면 상품 목록: 사용상품 행이 없어도 발주 가능으로 보인다.
            var listed=new SupplierIntegrationService(catalog,mock(SupplierSiteService.class)).offers(query(String.valueOf(offer.get("supplier_item_code"))));
            assertThat(listed).singleElement().satisfies(r->{
                assertThat(r.get("account_ingredient_product_id")).isNull();
                assertThat(r.get("orderable_yn")).isEqualTo("Y");
            });

            // 3) 발주: 미연결 식재료를 원본으로 발주하면 사용상품 행이 생기고, 공급사 접수 시 그 식재료에 자동 연결된다.
            var json=new ObjectMapper().findAndRegisterModules(); // 운영 Spring ObjectMapper와 같이 날짜 타입 처리
            var adapter=mock(OurhomeOrderAdapter.class);
            String code=String.valueOf(offer.get("supplier_item_code"));BigDecimal price=new BigDecimal(String.valueOf(offer.get("purchase_price")));
            when(adapter.validate(anyString(),anyString(),eq(code),anyMap(),any())).thenReturn(json.createObjectNode().put("itemCode",code).put("price",price));
            when(adapter.request(anyMap(),anyList())).thenReturn(json.createObjectNode());
            when(adapter.submit(any(),anyMap(),anyList())).thenAnswer(inv->{
                var response=json.createObjectNode();var body=response.putObject("dataBody").put("resCd","S0000");
                body.putArray("data").addObject().put("clientOrdItem","1").put("itemCode",code).put("resCd","S0000");
                return response;
            });
            var service=new OrderWorkflowService(orders,catalog,mock(WelstoryItemLookupService.class),json,manager,mock(ShortageProcurementService.class));
            service.setOurhome(adapter);
            BigDecimal qty=new BigDecimal(String.valueOf(offer.getOrDefault("minimum_order_qty","1")));
            if(qty.signum()<=0) qty=BigDecimal.ONE;
            var payload=json.createObjectNode();
            payload.putObject("order").put("account_id",ACCOUNT).put("sold_to",SITE).put("supplier_code","OURHOME")
                .put("request_key","ROLLBACK-"+UUID.randomUUID()).put("req_delivery_date",DATE.replace("-","")).put("user_id","rollback-test");
            payload.putArray("items").addObject().put("supplier_item_code",code).put("order_qty",qty)
                .put("source_ingredient_id",ingredient).put("delivery_terms_confirmed",true);
            var result=service.order(payload);
            assertThat(result.get("status")).as("발주 결과 %s",result).isEqualTo("ORDERED");
            String orderId=String.valueOf(result.get("purchase_order_id"));
            var created=accountRow(guarded,productId);
            assertThat(created).as("발주 저장 때 사용상품 행 생성").isNotNull();
            assertThat(created.get("mapped_ingredient_id")).as("접수 시 자동 연결").isEqualTo(ingredient);
            var item=orders.items(Map.of("account_id",ACCOUNT,"purchase_order_id",orderId)).get(0);
            assertThat(item.get("ingredient_link_status")).isEqualTo("LINKED");
            assertThat(String.valueOf(item.get("account_ingredient_product_id"))).isEqualTo(String.valueOf(created.get("account_ingredient_product_id")));

            // 4) 입고: 연결된 사용상품으로 재고가 생기고 연결은 유지된다.
            var request=json.createObjectNode().put("account_id",ACCOUNT).put("purchase_order_id",orderId)
                .put("receipt_key",UUID.randomUUID().toString()).put("receipt_date",DATE);
            request.putArray("items").addObject().put("client_ord_item","1").put("received_qty",qty);
            assertThat(new ReceiptPostingService(catalog,orders).receive(request).get("status")).isEqualTo("RECEIVED");
            var linked=accountRow(guarded,productId);
            assertThat(linked.get("mapped_ingredient_id")).as("입고 후 연결 유지").isEqualTo(ingredient);
            try(var st=guarded.prepareStatement("SELECT COALESCE(SUM(current_base_qty),0) FROM the_full_order.tb_account_inventory_balance WHERE account_ingredient_product_id=?")){
                st.setObject(1,linked.get("account_ingredient_product_id"));
                try(var rs=st.executeQuery()){rs.next();assertThat(rs.getBigDecimal(1)).as("입고 재고").isPositive();}
            }
            System.out.println("UNUSED_ORDER_OK product="+code+" ingredient="+ingredient+" qty="+qty);
        } finally {
            real.rollback();
            if(productId!=null) assertThat(accountRow(real,productId)).as("롤백 후 발주 때 만든 사용상품 행도 사라짐").isNull();
            real.close();
        }
    }

    private static Map<String,Object> query(String code) {
        Map<String,Object> q=new HashMap<>();
        q.put("account_id",ACCOUNT);q.put("external_site_code",SITE);q.put("supplier_code","OURHOME");
        q.put("delivery_date",DATE);q.put("price_at",DATE);q.put("supplier_item_code",code);
        return q;
    }

    private static Map<String,Object> accountRow(Connection c,String productId) throws SQLException {
        try(var st=c.prepareStatement("SELECT account_ingredient_product_id,mapped_ingredient_id FROM the_full_order.tb_account_ingredient_product WHERE account_id=? AND supplier_product_id=?")){
            st.setString(1,ACCOUNT);st.setString(2,productId);
            try(var rs=st.executeQuery()){
                if(!rs.next()) return null;
                Map<String,Object> row=new HashMap<>();
                row.put("account_ingredient_product_id",rs.getObject(1));row.put("mapped_ingredient_id",rs.getObject(2));
                return row;
            }
        }
    }
}
