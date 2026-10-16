package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.math.BigDecimal;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.*;

/** 직접구매·기초재고 추정 단가의 실제 SQL. 전용 합성 데이터만 쓰고 항상 롤백. 공급사 호출·스키마 변경 없음. */
@EnabledIfEnvironmentVariable(named="RUN_DIRECT_PURCHASE_DB",matches="true")
class LocalDirectPurchaseDatabaseTest {
    @Test void directPurchaseAndEstimatedOpeningUseActualSql() throws Exception {
        Path root=Path.of("src/main/resources");var props=new Properties();
        try(var reader=Files.newBufferedReader(root.resolve("application.properties"))){props.load(reader);}
        for(String imp:props.getProperty("spring.config.import","").split(",")) {
            var path=root.resolve(imp.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(path.startsWith(root)&&Files.isRegularFile(path))try(var reader=Files.newBufferedReader(path)){props.load(reader);}
        }
        String url=props.getProperty("spring.datasource.url");assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        try(var c=DriverManager.getConnection(url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"))) {
            c.setAutoCommit(false);
            var cfg=new Configuration(new Environment("direct-rollback",new JdbcTransactionFactory(),new SingleConnectionDataSource(c,true)));
            for(String name:List.of("MenuMapper","InventoryMapper","SupplierCatalogMapper","OrderWorkflowMapper","SupplierIntegrationMapper"))
                try(var in=Files.newInputStream(root.resolve("mybatis-mapper/"+name+".xml"))) {
                    new XMLMapperBuilder(in,cfg,"mybatis-mapper/"+name+".xml",cfg.getSqlFragments()).parse();
                }
            cfg.addMapper(DirectPurchaseMapper.class);cfg.addMapper(IngredientLinkMapper.class);
            try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession(c)) {
                try {
                    String id="DIRP"+UUID.randomUUID().toString().replace("-","").substring(0,12);
                    long supplier=write(c,"INSERT INTO tb_supplier(supplier_code,supplier_name,supplier_type) VALUES(?,?,'FOOD')",id,id);
                    write(c,"INSERT INTO tb_ingredient_master(ingredient_id,ingredient_name_raw,ingredient_name_std,base_unit) VALUES(?,?,?,'g')",id,id,id);
                    write(c,"INSERT INTO tb_account_ingredient_master(account_id,ingredient_id,base_unit) VALUES(?,?,'g')",id,id);
                    long product=write(c,"INSERT INTO tb_supplier_ingredient_product(supplier_id,supplier_item_code,product_name,order_unit,package_unit,base_qty,base_unit) VALUES(?,?,?,'PAC','g',1000,'g')",supplier,id,id);
                    long link=write(c,"INSERT INTO tb_account_ingredient_product(account_id,supplier_product_id,mapped_ingredient_id,preferred_yn) VALUES(?,?,?,'Y')",id,product,id);
                    long site=write(c,"INSERT INTO tb_supplier_site(supplier_id,external_site_code) VALUES(?,?)",supplier,id);
                    write(c,"INSERT INTO tb_supplier_account_site(account_id,supplier_id,supplier_site_id,external_site_code) VALUES(?,?,?,?)",id,supplier,site,id);
                    long offer=write(c,"INSERT INTO tb_supplier_site_product_offer(supplier_site_id,supplier_product_id) VALUES(?,?)",site,product);
                    write(c,"INSERT INTO tb_supplier_site_product_price(supplier_offer_id,purchase_price,effective_from,effective_to) VALUES(?,5000,DATE_SUB(NOW(),INTERVAL 1 DAY),NULL)",offer);
                    long balance=write(c,"INSERT INTO tb_account_inventory_balance(account_id,account_ingredient_product_id,location_id,current_base_qty,base_unit) VALUES(?,?,'L999',2000,'g')",id,link);

                    var inventory=new InventoryService(session.getMapper(InventoryMapper.class),org.mockito.Mockito.mock(StockAvailabilityService.class));
                    var rows=inventory.AccountInventoryList(new HashMap<>(Map.of("account_id",id)));
                    assertThat(rows).singleElement().satisfies(r -> {
                        assertThat(r.get("opening_available")).isEqualTo("Y");
                        assertThat(new BigDecimal(r.get("estimated_unit_cost").toString())).isEqualByComparingTo("5");
                        assertThat(r.get("estimated_cost_source")).isEqualTo("SITE_PRICE");
                    });
                    var opening=new HashMap<String,Object>(Map.of("inventory_balance_id",balance,"current_qty",2,"current_unit","kg","user_id","T"));
                    assertThat(inventory.opening(opening)).containsEntry("cost_estimated",true);
                    session.clearCache();
                    rows=inventory.AccountInventoryList(new HashMap<>(Map.of("account_id",id)));
                    assertThat(rows).singleElement().satisfies(r -> {
                        assertThat(new BigDecimal(r.get("inventory_amount").toString())).isEqualByComparingTo("10000");
                        assertThat(r.get("opening_cost_estimated")).isEqualTo("Y");
                        assertThat(r.get("opening_available")).isEqualTo("N");
                        assertThat(r).doesNotContainKey("estimated_unit_cost");
                    });

                    var service=new DirectPurchaseService(session.getMapper(DirectPurchaseMapper.class),session.getMapper(IngredientLinkMapper.class),
                        session.getMapper(OrderWorkflowMapper.class),session.getMapper(SupplierIntegrationMapper.class));
                    var first=purchase(id,"REQ-"+id+"-1","3","kg","9000");
                    var saved=service.record(first);
                    assertThat(saved).containsEntry("product_created",true).containsEntry("preferred",false);
                    long directLink=Long.parseLong(saved.get("account_ingredient_product_id").toString());
                    long directProduct=Long.parseLong(saved.get("supplier_product_id").toString());
                    assertThat(one(c,"SELECT CONCAT(s.supplier_code,'|',s.supplier_type,'|',p.product_name,'|',CAST(p.base_qty AS DOUBLE),p.base_unit,'|',IFNULL(p.ingredient_id,'NULL')) FROM tb_supplier_ingredient_product p JOIN tb_supplier s ON s.supplier_id=p.supplier_id WHERE p.supplier_product_id=?",directProduct))
                        .isEqualTo("DIRECT|DIRECT|양파 (쿠팡)|1g|NULL");
                    assertThat(one(c,"SELECT CONCAT(mapped_ingredient_id,'|',preferred_yn,'|',CAST(link_base_qty AS DOUBLE),link_base_unit) FROM tb_account_ingredient_product WHERE account_ingredient_product_id=?",directLink)).isEqualTo(id+"|N|1g");
                    assertThat(one(c,"SELECT CONCAT(CAST(current_base_qty AS DOUBLE),'|',CAST(average_unit_cost AS DOUBLE),'|',CAST(inventory_amount AS DOUBLE)) FROM tb_account_inventory_balance WHERE account_ingredient_product_id=?",directLink)).isEqualTo("3000|3|9000");
                    assertThat(one(c,"SELECT CONCAT(received_date,'|',CAST(remaining_base_qty AS DOUBLE),'|',CAST(receipt_unit_cost AS DOUBLE),'|',status,'|',expiration_date) FROM tb_account_inventory_lot WHERE account_ingredient_product_id=?",directLink))
                        .isEqualTo(LocalDate.now()+"|3000|3|AVAILABLE|2099-12-31");
                    assertThat(one(c,"SELECT CONCAT(movement_type,'|',reference_type,'|',CAST(quantity_delta AS DOUBLE),'|',CAST(amount_delta AS DOUBLE)) FROM tb_account_inventory_movement WHERE account_ingredient_product_id=?",directLink))
                        .isEqualTo("IN|DIRECT_PURCHASE|3000|9000");

                    assertThat(service.record(purchase(id,"REQ-"+id+"-1","3","kg","9000"))).containsEntry("replayed",true);
                    var second=service.record(purchase(id,"REQ-"+id+"-2","1000","g","5000"));
                    assertThat(second).containsEntry("product_created",false).containsEntry("account_ingredient_product_id",directLink);
                    assertThat(one(c,"SELECT CONCAT(CAST(current_base_qty AS DOUBLE),'|',CAST(average_unit_cost AS DOUBLE)) FROM tb_account_inventory_balance WHERE account_ingredient_product_id=?",directLink)).isEqualTo("4000|3.5");
                    assertThat(one(c,"SELECT COUNT(*) FROM tb_account_inventory_lot WHERE account_ingredient_product_id=?",directLink)).isEqualTo("2");
                    assertThat(one(c,"SELECT preferred_yn FROM tb_account_ingredient_product WHERE account_ingredient_product_id=?",link)).as("공급사 우선 상품 유지").isEqualTo("Y");

                    session.clearCache();
                    var catalog=session.getMapper(SupplierCatalogMapper.class);
                    assertThat(catalog.products(new HashMap<>(Map.of("supplier_product_id",directProduct)))).as("직접구매 상품은 본사 상품 목록에 나오지 않음").isEmpty();
                    assertThat(catalog.suppliers(new HashMap<>())).noneMatch(s -> "DIRECT".equals(s.get("supplier_code")));
                    var price=session.getMapper(MenuMapper.class).productPlanningPrice(new HashMap<>(Map.of("account_id",id,"ingredient_id",id,"supplier_product_id",directProduct)));
                    assertThat(price.get("auto_source")).isEqualTo("RECENT_RECEIPT");
                    assertThat(new BigDecimal(price.get("auto_unit_cost").toString())).isEqualByComparingTo("5");
                } finally { c.rollback(); }
            }
        }
    }

    private static Map<String,Object> purchase(String id,String key,String qty,String unit,String amount) {
        var b=new HashMap<String,Object>();
        b.put("account_id",id);b.put("ingredient_id",id);b.put("product_name","양파 (쿠팡)");b.put("purchase_date",LocalDate.now().toString());
        b.put("quantity",qty);b.put("unit",unit);b.put("total_amount",amount);b.put("request_key",key);b.put("expiration_date","2099-12-31");b.put("user_id","T");
        return b;
    }
    private static String one(Connection c,String sql,Object... args) throws SQLException {
        try(var s=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
            try(var r=s.executeQuery()) { assertThat(r.next()).isTrue(); return r.getString(1); } }
    }
    private static long write(Connection c,String sql,Object... args) throws SQLException {
        try(var statement=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            statement.executeUpdate();
            try(var keys=statement.getGeneratedKeys()) { return keys.next()?keys.getLong(1):0; }
        }
    }
}
