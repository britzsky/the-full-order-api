package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.io.Reader;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.IngredientLinkMapper;

@EnabledIfEnvironmentVariable(named="RUN_LOCAL_ACCOUNT_INGREDIENT_TESTS",matches="true")
class LocalAccountIngredientDatabaseTest {
    @Test void validateAccountScopedQueries() throws Exception {
        Path resources=Path.of("src/main/resources");
        Properties props=new Properties();
        try(Reader r=Files.newBufferedReader(resources.resolve("application.properties"))){props.load(r);}
        for(String imp:props.getProperty("spring.config.import","").split(",")) {
            Path p=resources.resolve(imp.trim().replace("optional:","").replace("classpath:","")).normalize();
            if(p.startsWith(resources)&&Files.isRegularFile(p))try(Reader r=Files.newBufferedReader(p)){props.load(r);}
        }
        String url=props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        var ds=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"));
        try(var c=ds.getConnection();var statement=c.createStatement()) {
            boolean exists;
            try(var rs=statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='the_full_order' AND table_name='tb_account_ingredient_product' AND column_name='mapped_ingredient_id'")){rs.next();exists=rs.getInt(1)>0;}
            assertThat(exists).as("Apply the schema change described in docs/TASK_STATUS.md before running this test").isTrue();
        }
        var cfg=new Configuration(new Environment("local-account-ingredients",new JdbcTransactionFactory(),ds));
        for(String name:List.of("InventoryMapper","MenuMapper","AccountMenuRecipeMapper","SupplierCatalogMapper","SupplierIntegrationMapper","ShortageProcurementMapper","OrderWorkflowMapper")) {
            String resource="mybatis-mapper/"+name+".xml";
            try(var in=Files.newInputStream(resources.resolve(resource))){new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();}
        }
        cfg.addMapper(IngredientLinkMapper.class);
        try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession(false)) {
            try {
                String account="__ingredient_validation__";
                String menu="__ingredient_validation__";
                try(var stmt=session.getConnection().createStatement();var rs=stmt.executeQuery("SELECT account_id,menu_id FROM the_full_order.tb_account_recipe_detail LIMIT 1")) {
                    if(rs.next()){account=rs.getString(1);menu=rs.getString(2);}
                }
                var p=new HashMap<String,Object>();p.put("account_id",account);p.put("menu_id",menu);p.put("delivery_date",LocalDate.now().toString());p.put("date_to",LocalDate.now().toString());p.put("limit",10);p.put("offset",0);
                for(String id:List.of("InventoryMapper.ingredientMasterList","InventoryMapper.AccountInventoryList","MenuMapper.MenuList","MenuMapper.AccountMenuList","MenuMapper.AccountDetailList","AccountMenuRecipeMapper.ingredients","SupplierCatalogMapper.accountProducts","SupplierIntegrationMapper.offers","ShortageProcurementMapper.stock","ShortageProcurementMapper.targets","ShortageProcurementMapper.incoming","OrderWorkflowMapper.items"))
                    assertThatCode(() -> session.selectList("com.example.demo.mapper."+id,p)).as(id).doesNotThrowAnyException();
                // Roll back an actual account override; common catalog and other accounts must stay unchanged.
                try(var stmt=session.getConnection().createStatement();var rs=stmt.executeQuery("SELECT ap.account_id,ap.supplier_product_id,p.ingredient_id FROM the_full_order.tb_account_ingredient_product ap JOIN the_full_order.tb_supplier_ingredient_product p ON p.supplier_product_id=ap.supplier_product_id LIMIT 1")) {
                    if(rs.next()) {
                        String a=rs.getString(1),ingredient=rs.getString(3);long product=rs.getLong(2);
                        var change=Map.<String,Object>of("account_id",a,"supplier_product_id",product,"ingredient_id","__account_scope_test__");
                        String before=otherMappings(session.getConnection(),a,product);
                        assertThat(session.getMapper(IngredientLinkMapper.class).setIngredient(change)).isEqualTo(1);
                        session.clearCache();
                        var rows=session.<Map<String,Object>>selectList("com.example.demo.mapper.SupplierCatalogMapper.accountProducts",Map.of("account_id",a,"ingredient_id","__account_scope_test__"));
                        assertThat(rows).anySatisfy(row -> assertThat(((Number)row.get("supplier_product_id")).longValue()).isEqualTo(product));
                        assertThat(otherMappings(session.getConnection(),a,product)).isEqualTo(before);
                        try(var check=session.getConnection().prepareStatement("SELECT ingredient_id FROM the_full_order.tb_supplier_ingredient_product WHERE supplier_product_id=?")) {
                            check.setLong(1,product);try(var result=check.executeQuery()){result.next();assertThat(result.getString(1)).isEqualTo(ingredient);}
                        }
                    }
                }
            } finally { session.rollback(); }
        }
    }
    private String otherMappings(Connection c,String account,long product)throws Exception {
        try(var s=c.prepareStatement("SELECT account_id,mapped_ingredient_id FROM the_full_order.tb_account_ingredient_product WHERE supplier_product_id=? AND account_id<>? ORDER BY account_id")) {
            s.setLong(1,product);s.setString(2,account);var result=new ArrayList<String>();
            try(var r=s.executeQuery()){while(r.next())result.add(r.getString(1)+":"+r.getString(2));}return result.toString();
        }
    }
}
