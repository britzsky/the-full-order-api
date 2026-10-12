package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.math.BigDecimal;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.example.demo.mapper.ShortageProcurementMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Synthetic account only, localhost only, all writes rolled back. No supplier calls. */
@EnabledIfEnvironmentVariable(named="RUN_ORDER_AUTOMATION_DB",matches="true")
class LocalOrderAutomationDatabaseTest {
    @Test void addedProductPreferenceUndoAndCombinedStockUseActualSql() throws Exception {
        Path root=Path.of("src/main/resources"); var props=new Properties();
        try(var reader=Files.newBufferedReader(root.resolve("application.properties"))) { props.load(reader); }
        for(String imp:props.getProperty("spring.config.import","").split(",")) {
            var path=root.resolve(imp.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(path.startsWith(root)&&Files.isRegularFile(path)) try(var reader=Files.newBufferedReader(path)) { props.load(reader); }
        }
        String url=props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        try(var c=DriverManager.getConnection(url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"))) {
            c.setAutoCommit(false);
            var cfg=new Configuration(new Environment("automation-rollback",new JdbcTransactionFactory(),new SingleConnectionDataSource(c,true)));
            for(String name:List.of("OrderWorkflowMapper","SupplierIntegrationMapper","ShortageProcurementMapper"))
                try(var in=Files.newInputStream(root.resolve("mybatis-mapper/"+name+".xml"))) {
                    new XMLMapperBuilder(in,cfg,name,cfg.getSqlFragments()).parse();
                }
            try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession(c)) {
                try {
                    String id="AUTO"+UUID.randomUUID().toString().replace("-","").substring(0,12);
                    long supplier=write(c,"INSERT INTO tb_supplier(supplier_code,supplier_name,supplier_type) VALUES(?,?,'FOOD')",id,id);
                    write(c,"INSERT INTO tb_ingredient_master(ingredient_id,ingredient_name_raw,ingredient_name_std,base_unit) VALUES(?,?,?,'g')",id,id,id);
                    write(c,"INSERT INTO tb_account_ingredient_master(account_id,ingredient_id,base_unit) VALUES(?,?,'g')",id,id);
                    long old=product(c,supplier,id+"OLD"), next=product(c,supplier,id+"NEW");
                    var orders=session.getMapper(OrderWorkflowMapper.class);
                    var catalog=session.getMapper(SupplierIntegrationMapper.class);
                    var pack=new HashMap<String,Object>(Map.of("supplier_product_id",next,"base_qty",1200,"base_unit","g","package_qty",1200,"package_unit","g"));
                    assertThat(catalog.refreshUnusedOurhomePack(pack)).isEqualTo(1);
                    long oldLink=write(c,"INSERT INTO tb_account_ingredient_product(account_id,supplier_product_id,mapped_ingredient_id,preferred_yn) VALUES(?,?,?,'Y')",id,old,id);
                    long nextLink=write(c,"INSERT INTO tb_account_ingredient_product(account_id,supplier_product_id,preferred_yn) VALUES(?,?,'N')",id,next);
                    write(c,"INSERT INTO tb_account_inventory_balance(account_id,account_ingredient_product_id,location_id,current_base_qty,base_unit) VALUES(?,?,'L999',1000,'g')",id,oldLink);

                    var row=new HashMap<String,Object>();
                    row.put("account_id",id); row.put("source_ingredient_id",id); row.put("supplier_id",supplier);
                    row.put("supplier_product_id",next); row.put("account_ingredient_product_id",nextLink);
                    row.put("purchase_order_id",UUID.randomUUID().toString()); row.put("client_ord",id); row.put("client_ord_item","1");
                    row.put("supplier_item_code",id+"NEW"); row.put("requested_delivery_date","2099-10-01");
                    row.put("order_qty",1); row.put("order_unit","PAC"); row.put("base_qty",1200); row.put("base_unit","g");
                    row.put("purchase_price",6000); row.put("line_amount",6000); row.put("total_amount",6000);
                    row.put("ingredient_link_status","PENDING");
                    var context=orders.linkContext(row);
                    row.put("link_context_snapshot",OrderWorkflowService.linkContextFingerprint(context));
                    row.put("product_snapshot","{}");
                    orders.insertOrder(row); orders.insertItem(row);
                    row.put("status","ORDERED"); orders.updateOrder(row);
                    row.put("item_status","ORDERED"); orders.updateItem(row);
                    var item=orders.items(row).get(0);
                    var linking=new OrderProductLinking(orders,new ObjectMapper());
                    linking.apply(item,true);
                    assertThat(orders.items(row).get(0).get("ingredient_link_status")).isEqualTo("LINKED");
                    assertThat(orders.sourceLinks(row)).hasSize(2).filteredOn(p -> "Y".equals(p.get("preferred_yn")))
                        .singleElement().satisfies(p -> assertThat(p.get("supplier_product_id").toString()).isEqualTo(Long.toString(next)));
                    assertThat(orders.newerAcceptedSourceOrders(row)).isZero();
                    assertThat(catalog.refreshUnusedOurhomePack(pack)).as("linked products retain their stock conversion").isZero();
                    write(c,"INSERT INTO tb_account_inventory_balance(account_id,account_ingredient_product_id,location_id,current_base_qty,base_unit) VALUES(?,?,'L999',500,'g')",id,nextLink);
                    session.clearCache();
                    var stock=session.getMapper(ShortageProcurementMapper.class).stock(new HashMap<>(Map.of("account_id",id,"date_from","2099-10-01")));
                    assertThat(stock).hasSize(2);
                    assertThat(stock.stream().map(p -> new BigDecimal(p.get("quantity").toString())).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("1500");
                    linking.undo(orders.items(row).get(0));
                    assertThat(orders.sourceLinks(row)).hasSize(2).filteredOn(p -> "Y".equals(p.get("preferred_yn")))
                        .singleElement().satisfies(p -> assertThat(p.get("supplier_product_id").toString()).isEqualTo(Long.toString(old)));
                    assertThat(orders.productStockUnits(row)).containsExactly("g");
                    var foreign=new HashMap<>(row); foreign.put("account_id",id+"OTHER");
                    assertThat(orders.items(foreign)).isEmpty();
                    assertThat(orders.lockSourceIngredient(foreign)).isNull();
                } finally { session.rollback(true); }
            } finally { if (!c.isClosed()) c.rollback(); }
        }
    }

    private long product(Connection c,long supplier,String id) throws SQLException {
        return write(c,"INSERT INTO tb_supplier_ingredient_product(supplier_id,supplier_item_code,product_name,order_unit,package_unit,base_qty,base_unit) VALUES(?,?,?,'PAC','g',1000,'g')",supplier,id,id);
    }
    private static long write(Connection c,String sql,Object... args) throws SQLException {
        try(var statement=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            statement.executeUpdate();
            try(var keys=statement.getGeneratedKeys()) { return keys.next()?keys.getLong(1):0; }
        }
    }
}
