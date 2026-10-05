package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.io.Reader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 로컬 DB에서 실제 SQL을 실행한 뒤 전부 롤백한다. 외부 API는 호출하지 않는다. */
@EnabledIfEnvironmentVariable(named="RUN_LOCAL_DB_TESTS", matches="true")
class LocalOrderDatabaseTest {
    @Test void orderAndPartialReceiptsUseActualSchemaAndRollback() throws Exception {
        Path resources=Path.of("src/main/resources");
        Properties properties=new Properties();
        try(Reader reader=Files.newBufferedReader(resources.resolve("application.properties"))){properties.load(reader);}
        for(String imported:properties.getProperty("spring.config.import","").split(",")){
            String name=imported.trim().replace("optional:","").replace("classpath:","");
            if(name.isBlank())continue;
            Path path=resources.resolve(name).normalize();
            if(path.startsWith(resources) && Files.isRegularFile(path))try(Reader reader=Files.newBufferedReader(path)){properties.load(reader);}
        }
        String url=properties.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        var source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url,properties.getProperty("spring.datasource.username"),properties.getProperty("spring.datasource.password"));
        var configuration=new Configuration(new Environment("local-rollback-test",new JdbcTransactionFactory(),source));
        for(String name:List.of("OrderWorkflowMapper","SupplierIntegrationMapper","ProcurementMapper","DiscoveryMapper","InventoryMapper","MenuMapper")){
            String resource="mybatis-mapper/"+name+".xml";
            try(var stream=Files.newInputStream(resources.resolve(resource))){new XMLMapperBuilder(stream,configuration,resource,configuration.getSqlFragments()).parse();}
        }
        var factory=new SqlSessionFactoryBuilder().build(configuration);
        String id=UUID.randomUUID().toString();
        try(SqlSession session=factory.openSession(false)){
            try {
                Map<String,Object> row=new HashMap<>();
                try(Statement statement=session.getConnection().createStatement();ResultSet rs=statement.executeQuery("SELECT ap.account_id,ap.account_ingredient_product_id,p.supplier_id,p.supplier_item_code,p.order_unit,p.base_unit,p.base_qty FROM the_full_order.tb_account_ingredient_product ap JOIN the_full_order.tb_supplier_ingredient_product p ON p.supplier_product_id=ap.supplier_product_id WHERE p.base_qty>0 AND ap.active_yn='Y' LIMIT 1")){
                    assertThat(rs.next()).as("검증용 기존 상품 연결 필요").isTrue();
                    for(int c=1;c<=rs.getMetaData().getColumnCount();c++)row.put(rs.getMetaData().getColumnLabel(c),rs.getObject(c));
                }
                row.put("purchase_order_id",id);row.put("client_ord","ROLLBACK"+id.substring(0,8));row.put("sold_to",null);
                row.put("requested_delivery_date",java.time.LocalDate.now().toString());row.put("total_amount",10000);
                row.put("client_note","롤백 통합 검증");row.put("user_id",null);row.put("client_ord_item","1");row.put("menu_id",null);
                row.put("order_qty",10);row.put("purchase_price",1000);row.put("line_amount",10000);
                var stockBefore=stockQuantity(session.getConnection(),row);
                var conversion=new java.math.BigDecimal(row.get("base_qty").toString());
                var orders=session.getMapper(OrderWorkflowMapper.class);var catalog=session.getMapper(SupplierIntegrationMapper.class);
                catalog.scheduledCatalogTargets();catalog.scheduledReceiptTargets();
                Map<String,Object> search=new HashMap<>(row);search.put("query","검증");search.put("limit",5);search.put("meal_date",java.time.LocalDate.now().toString());search.put("user_id","");
                var discovery=session.getMapper(com.example.demo.mapper.DiscoveryMapper.class);
                // A recipe-only match must survive even when the menu title does not contain the query.
                String recipeMenu;
                try(var statement=session.getConnection().createStatement();var result=statement.executeQuery(
                    "SELECT m.menu_id FROM the_full_order.tb_menu_master m JOIN the_full_order.tb_recipe_detail rd ON rd.menu_id=m.menu_id WHERE m.del_yn='N' LIMIT 1")) {
                    assertThat(result.next()).as("레시피 검색 검증용 메뉴 필요").isTrue();recipeMenu=result.getString(1);
                }
                String recipeQuery="RECIPE"+id.replace("-", "");
                try(var statement=session.getConnection().prepareStatement("UPDATE the_full_order.tb_recipe_detail SET ingredient_name_raw=? WHERE menu_id=?")) {
                    statement.setString(1,recipeQuery);statement.setString(2,recipeMenu);statement.executeUpdate();
                }
                var recipeSearch=new HashMap<String,Object>(search);recipeSearch.put("query",recipeQuery);recipeSearch.put("limit",30);
                var recipeResults=discovery.search(recipeSearch);
                var menuMapper=session.getMapper(com.example.demo.mapper.MenuMapper.class);
                assertThat(menuMapper.MenuList(search)).filteredOn(r->recipeMenu.equals(r.get("menu_id").toString())).singleElement()
                    .satisfies(r->assertThat(r.get("recipe_ingredient_names").toString()).contains(recipeQuery));
                menuMapper.AccountMenuList(search);
                assertThat(recipeResults).filteredOn(r->recipeMenu.equals(r.get("entity_id").toString())).singleElement()
                    .satisfies(r->{assertThat(r.get("matched_ingredients")).isEqualTo(recipeQuery);assertThat(r.get("title").toString()).doesNotContain(recipeQuery);});
                try(var statement=session.getConnection().prepareStatement("UPDATE the_full_order.tb_menu_master SET del_yn='Y' WHERE menu_id=?")) {
                    statement.setString(1,recipeMenu);statement.executeUpdate();
                }
                session.clearCache();
                assertThat(discovery.search(recipeSearch)).noneMatch(r->recipeMenu.equals(r.get("entity_id").toString()));
                try(var statement=session.getConnection().prepareStatement("UPDATE the_full_order.tb_menu_master SET del_yn='N' WHERE menu_id=?")) {
                    statement.setString(1,recipeMenu);statement.executeUpdate();
                }
                discovery.search(search);discovery.recommendedMenus(search);
                search.put("table_id","ROLLBACK_TEST");
                session.getMapper(com.example.demo.mapper.ProcurementMapper.class).mealPlanIngredientAnalysis(search);
                session.getMapper(com.example.demo.mapper.ProcurementMapper.class).mealPlanSummary(search);
                // 서비스가 준비하는 필수 스냅샷 상태를 SQL 직접 검증 fixture에도 명시한다.
                row.put("ingredient_link_status","NONE");
                row.put("product_snapshot","{}");
                orders.insertOrder(row);orders.insertItem(row);
                row.put("request_payload","{}");row.put("fingerprint",id);orders.insertExchange(row);
                row.put("status","ORDERED");row.put("supplier_result_code","TEST");row.put("supplier_result_message","ROLLBACK");orders.updateOrder(row);
                row.put("response_payload","{}");orders.insertReconciliation(row);
                assertThat(stockQuantity(session.getConnection(),row)).as("발주 성공만으로 현재고를 늘리지 않음").isEqualByComparingTo(stockBefore);
                var receipts=new ReceiptPostingService(catalog,orders);var json=new ObjectMapper();
                var request=json.createObjectNode();request.put("account_id",row.get("account_id").toString()).put("purchase_order_id",id)
                    .put("receipt_key",UUID.randomUUID().toString()).put("receipt_date",java.time.LocalDate.now().toString());
                request.putArray("items").addObject().put("client_ord_item","1").put("received_qty",4);
                assertThat(receipts.receive(request).get("status")).isEqualTo("PARTIAL_RECEIVED");
                var partialStock=stockBefore.add(conversion.multiply(new java.math.BigDecimal("4")));
                assertThat(stockQuantity(session.getConnection(),row)).as("4개 입고를 기준단위로 환산하여 현재고에 반영").isEqualByComparingTo(partialStock);
                assertThat(receipts.receive(request).get("received_item_count")).isEqualTo(0);
                assertThat(stockQuantity(session.getConnection(),row)).as("동일 입고 재처리 시 현재고 유지").isEqualByComparingTo(partialStock);
                request.put("receipt_key",UUID.randomUUID().toString());((com.fasterxml.jackson.databind.node.ObjectNode)request.path("items").get(0)).put("received_qty",6);
                assertThat(receipts.receive(request).get("status")).isEqualTo("RECEIVED");
                assertThat(new java.math.BigDecimal(orders.items(row).get(0).get("received_order_qty").toString())).isEqualByComparingTo("10");
                assertThat(stockQuantity(session.getConnection(),row)).as("누적 10개 입고를 현재고에 정확히 반영").isEqualByComparingTo(stockBefore.add(conversion.multiply(new java.math.BigDecimal("10"))));
                var inventoryRows=session.getMapper(com.example.demo.mapper.InventoryMapper.class).AccountInventoryList(row);
                var displayedStock=inventoryRows.stream()
                    .filter(balance->balance.get("account_ingredient_product_id").toString().equals(row.get("account_ingredient_product_id").toString()))
                    .map(balance->new java.math.BigDecimal(balance.get("current_base_qty").toString()))
                    .reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
                assertThat(displayedStock).as("거래처 재고 화면 조회 SQL에서도 입고 후 수량 확인")
                    .isEqualByComparingTo(stockBefore.add(conversion.multiply(new java.math.BigDecimal("10"))));
            } finally { session.rollback(); }
        }
        try(Connection connection=source.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT COUNT(*) FROM the_full_order.tb_account_purchase_order WHERE purchase_order_id=?")){
            statement.setString(1,id);try(ResultSet rs=statement.executeQuery()){rs.next();assertThat(rs.getInt(1)).isZero();}
        }
    }

    private static java.math.BigDecimal stockQuantity(Connection connection,Map<String,Object> row) throws SQLException {
        try(var statement=connection.prepareStatement("SELECT COALESCE(SUM(current_base_qty),0) FROM the_full_order.tb_account_inventory_balance WHERE account_id=? AND account_ingredient_product_id=?")) {
            statement.setObject(1,row.get("account_id"));statement.setObject(2,row.get("account_ingredient_product_id"));
            try(var result=statement.executeQuery()){result.next();return result.getBigDecimal(1);}
        }
    }
}
