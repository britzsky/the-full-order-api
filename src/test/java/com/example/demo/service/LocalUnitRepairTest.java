package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.io.Reader;
import java.math.BigDecimal;
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
import com.fasterxml.jackson.databind.ObjectMapper;

/** 기본은 전체 롤백. 명시적 APPLY_UNIT_REPAIR=true 실행만 백업 후 커밋한다. */
@EnabledIfEnvironmentVariable(named="RUN_LOCAL_DB_TESTS",matches="true")
class LocalUnitRepairTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Test void repairAndValidateLocalUnits() throws Exception {
        Path resources=Path.of("src/main/resources");Properties props=new Properties();
        try(Reader r=Files.newBufferedReader(resources.resolve("application.properties"))){props.load(r);}
        for(String imp:props.getProperty("spring.config.import","").split(",")) {
            Path p=resources.resolve(imp.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(p.startsWith(resources)&&Files.isRegularFile(p))try(Reader r=Files.newBufferedReader(p)){props.load(r);}
        }
        String url=props.getProperty("spring.datasource.url");assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        var ds=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"));
        var cfg=new Configuration(new Environment("unit-repair",new JdbcTransactionFactory(),ds));
        for(String name:List.of("MenuMapper","InventoryMapper","SupplierIntegrationMapper","SupplierCatalogMapper","AccountMenuRecipeMapper")) {
            String resource="mybatis-mapper/"+name+".xml";
            try(var in=Files.newInputStream(resources.resolve(resource))){new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();}
        }
        boolean apply="true".equals(System.getenv("APPLY_UNIT_REPAIR"));
        Path out=Path.of("deliverables/unit-repair",(apply?"apply-":"dry-run-")+System.currentTimeMillis());Files.createDirectories(out);
        var factory=new SqlSessionFactoryBuilder().build(cfg);
        try(SqlSession session=factory.openSession(false)) {
            Connection c=session.getConnection();
            try {
                // Historical movement/order quantities must never silently be reinterpreted.
                for(String table:List.of("tb_account_inventory_balance","tb_account_purchase_order_item","tb_account_meal_ingredient_requirement"))
                    assertThat(rows(c,"SELECT * FROM the_full_order."+table+" FOR UPDATE")).as("기존 거래가 있으면 별도 수량 이관 필요: "+table).isEmpty();
                Map<String,List<Map<String,Object>>> backup=new LinkedHashMap<>();
                for(String table:List.of("tb_supplier_ingredient_product","tb_ingredient_master","tb_account_ingredient_master","tb_recipe_detail","tb_account_recipe_detail"))
                    backup.put(table,rows(c,"SELECT * FROM the_full_order."+table+" FOR UPDATE"));
                Files.writeString(out.resolve("before.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(backup));
                List<Map<String,Object>> products=rows(c,"SELECT p.*,JSON_UNQUOTE(JSON_EXTRACT(o.provider_attributes,'$.standard')) spec,JSON_UNQUOTE(JSON_EXTRACT(o.provider_attributes,'$.unit')) raw_unit FROM the_full_order.tb_supplier_ingredient_product p JOIN the_full_order.tb_supplier s ON s.supplier_id=p.supplier_id LEFT JOIN the_full_order.tb_supplier_site_product_offer o ON o.supplier_offer_id=(SELECT MAX(x.supplier_offer_id) FROM the_full_order.tb_supplier_site_product_offer x WHERE x.supplier_product_id=p.supplier_product_id) WHERE s.supplier_code='WELSTORY'");
                int corrected=0,unknown=0;List<Map<String,Object>> plan=new ArrayList<>();
                Map<String,QuantityUnits.Pack> ingredients=new HashMap<>();Set<String> conflict=new HashSet<>();
                for(var p:products) {
                    // Only migrate legacy rows whose base unit still equals the original order unit.
                    if(!QuantityUnits.unit(p.get("base_unit")).equals(QuantityUnits.unit(p.get("raw_unit"))))continue;
                    var pack=QuantityUnits.pack(String.valueOf(p.get("order_unit")),Objects.toString(p.get("spec"),""));
                    var row=new LinkedHashMap<String,Object>();row.put("product_id",p.get("supplier_product_id"));row.put("spec",p.get("spec"));row.put("before_qty",p.get("base_qty"));row.put("before_unit",p.get("base_unit"));row.put("after_qty",pack.quantity());row.put("after_unit",pack.baseUnit());plan.add(row);
                    String ingredient=String.valueOf(p.get("ingredient_id"));
                    if(ingredients.containsKey(ingredient)&&!ingredients.get(ingredient).equals(pack))conflict.add(ingredient);
                    ingredients.put(ingredient,pack);
                    update(c,"UPDATE the_full_order.tb_supplier_ingredient_product SET base_qty=?,base_unit=?,package_qty=?,package_unit=? WHERE supplier_product_id=?",pack.quantity(),pack.baseUnit(),pack.quantity(),pack.baseUnit(),p.get("supplier_product_id"));
                    if(pack.quantity().signum()>0)corrected++;else unknown++;
                }
                for(var e:ingredients.entrySet()) {
                    var pack=e.getValue();if(conflict.contains(e.getKey()))continue;
                    // Require the existing master to still be in the legacy supplier order unit.
                    update(c,"UPDATE the_full_order.tb_ingredient_master SET base_unit=?,convert_value=?,needs_review=? WHERE ingredient_id=? AND LOWER(base_unit)=LOWER(order_unit)",pack.baseUnit(),pack.quantity().signum()>0?pack.quantity():BigDecimal.ONE,pack.quantity().signum()>0?0:1,e.getKey());
                    update(c,"UPDATE the_full_order.tb_account_ingredient_master a JOIN the_full_order.tb_ingredient_master i ON i.ingredient_id=a.ingredient_id SET a.base_unit=i.base_unit,a.needs_review=i.needs_review WHERE a.ingredient_id=?",e.getKey());
                }
                int recipeRows=0;
                for(String table:List.of("tb_recipe_detail","tb_account_recipe_detail")) {
                    String key=table.equals("tb_recipe_detail")?"recipe_detail_id":"account_recipe_detail_id";
                    for(var r:rows(c,"SELECT r.*,i.base_unit master_unit FROM the_full_order."+table+" r JOIN the_full_order.tb_ingredient_master i ON i.ingredient_id=r.ingredient_id")) {
                        QuantityUnits.recipe(r,String.valueOf(r.get("master_unit")));
                        update(c,"UPDATE the_full_order."+table+" SET qty_base=?,base_unit=?,qty_per_person=?,review_flag=? WHERE "+key+"=?",r.get("qty_base"),r.get("base_unit"),r.get("qty_per_person"),r.get("review_flag"),r.get(key));recipeRows++;
                    }
                }
                Map<String,Object> params=new HashMap<>();params.put("servingQty",1);params.put("menu_id","M0001");params.put("account_id",rows(c,"SELECT account_id FROM the_full_order.tb_account_ingredient_product LIMIT 1").get(0).get("account_id"));params.put("user_id","");
                var menus=session.getMapper(com.example.demo.mapper.MenuMapper.class);
                menus.MenuList(params);menus.DetailList(params);menus.AccountDetailList(params);menus.AccountMenuList(params);
                var inventory=session.getMapper(com.example.demo.mapper.InventoryMapper.class);inventory.ingredientMasterList(params);inventory.AccountInventoryList(params);
                for(String[] expected:new String[][]{{"W1000503878","300","20000","0.015"},{"W1000775583","100","10000","0.01"},{"W1000748126","50","1000","0.05"}}) {
                    var r=rows(c,"SELECT r.qty_base,r.base_unit,r.review_flag,p.base_qty FROM the_full_order.tb_recipe_detail r JOIN the_full_order.tb_supplier_ingredient_product p ON p.ingredient_id=r.ingredient_id WHERE r.ingredient_id='"+expected[0]+"'").get(0);
                    assertThat(r.get("base_unit")).isEqualTo("g");assertThat(new BigDecimal(r.get("qty_base").toString())).isEqualByComparingTo(expected[1]);assertThat(new BigDecimal(r.get("base_qty").toString())).isEqualByComparingTo(expected[2]);
                    assertThat(new BigDecimal(r.get("qty_base").toString()).divide(new BigDecimal(r.get("base_qty").toString()))).isEqualByComparingTo(expected[3]);
                }
                Files.writeString(out.resolve("plan.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
                Files.writeString(out.resolve("result.txt"),"verified=true\ncommit_requested="+apply+"\ncorrected_products="+corrected+"\nunknown_products="+unknown+"\nrecipe_rows="+recipeRows+"\n");
                if(apply){session.commit();Files.writeString(out.resolve("COMMITTED.txt"),"Committed after quantity and mapper verification.");}else session.rollback();
                System.out.println("UNIT_REPAIR "+out+" corrected="+corrected+" unknown="+unknown+" recipes="+recipeRows+" committed="+apply);
            } catch(Exception|AssertionError ex) {session.rollback();throw ex;}
        }
    }
    static List<Map<String,Object>> rows(Connection c,String sql)throws SQLException {
        List<Map<String,Object>> rows=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnLabel(i),r.getObject(i));rows.add(row);}}return rows;
    }
    static void update(Connection c,String sql,Object...values)throws SQLException {try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}}
}
