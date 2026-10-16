package com.example.demo.service;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_RICE_LINK_READ_ONLY", matches="true")
class LocalRiceLinkReadOnlyTest {
    @Test void inspectRiceLinksWithoutWrites() throws Exception {
        var props = new Properties();
        var root = Path.of("src/main/resources");
        try(var reader=Files.newBufferedReader(root.resolve("application.properties"))){props.load(reader);}
        for(String imp:props.getProperty("spring.config.import", "").split(",")) {
            var path=root.resolve(imp.trim().replace("optional:", "").replace("classpath:", "")).normalize();
            if(path.startsWith(root)&&Files.isRegularFile(path))try(var reader=Files.newBufferedReader(path)){props.load(reader);}
        }
        String url=props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        try(var c=DriverManager.getConnection(url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"))) {
            c.setReadOnly(true); c.setAutoCommit(false);
            for(String query:List.of(
                "SELECT CURRENT_DATE today,NOW() checked_at",
                "SELECT b.current_base_qty,b.base_unit,b.average_unit_cost,b.inventory_amount FROM tb_account_inventory_balance b JOIN tb_account_ingredient_product ap ON ap.account_ingredient_product_id=b.account_ingredient_product_id WHERE ap.account_id='20250819193526' AND ap.supplier_product_id=5716",
                "SELECT h.purchase_price,h.effective_from,h.effective_to,o.active_yn offer_active,a.active_yn site_active FROM tb_supplier_site_product_price h JOIN tb_supplier_site_product_offer o ON o.supplier_offer_id=h.supplier_offer_id JOIN tb_supplier_account_site a ON a.supplier_site_id=o.supplier_site_id WHERE a.account_id='20250819193526' AND o.supplier_product_id=5716 ORDER BY h.effective_from DESC LIMIT 8",
                "SELECT purchase_price,effective_from,effective_to FROM tb_supplier_ingredient_price_history WHERE supplier_product_id=5716 ORDER BY effective_from DESC LIMIT 3"
            ))try(var st=c.createStatement();var rs=st.executeQuery(query)) {
                while(rs.next()) {var out=new LinkedHashMap<String,Object>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)out.put(rs.getMetaData().getColumnLabel(i),rs.getObject(i));System.out.println("COST_DIAG "+out);}
            }
            for(String query:List.of(
                "SELECT ap.account_id,ap.mapped_ingredient_id,ap.active_yn link_active,ap.preferred_yn,p.supplier_product_id,p.product_name,p.active_yn product_active,ap.link_base_qty,ap.link_base_unit,b.current_base_qty,b.base_unit FROM tb_supplier_ingredient_product p LEFT JOIN tb_account_ingredient_product ap ON ap.supplier_product_id=p.supplier_product_id LEFT JOIN tb_account_inventory_balance b ON b.account_ingredient_product_id=ap.account_ingredient_product_id WHERE p.product_name LIKE '%흥국%'",
                "SELECT m.account_id,m.menu_id,m.menu_name,r.ingredient_id,r.ingredient_name_raw,r.qty_per_person,r.base_unit FROM tb_account_menu_master m JOIN tb_account_recipe_detail r ON r.account_id=m.account_id AND r.menu_id=m.menu_id WHERE m.menu_name LIKE '%쌀밥%'",
                "SELECT ap.account_id,ap.mapped_ingredient_id,ap.active_yn,ap.preferred_yn,p.supplier_product_id,p.product_name,p.active_yn product_active,b.current_base_qty,b.base_unit FROM tb_account_ingredient_product ap JOIN tb_supplier_ingredient_product p ON p.supplier_product_id=ap.supplier_product_id LEFT JOIN tb_account_inventory_balance b ON b.account_ingredient_product_id=ap.account_ingredient_product_id WHERE ap.mapped_ingredient_id='TIG001' OR (p.product_name LIKE '%쌀%' AND b.current_base_qty>0)"
            )) {
                try(var s=c.createStatement();var rows=s.executeQuery(query)) {
                    while(rows.next()) {
                        var row=new LinkedHashMap<String,Object>();
                        for(int i=1;i<=rows.getMetaData().getColumnCount();i++)row.put(rows.getMetaData().getColumnLabel(i),rows.getObject(i));
                        System.out.println(row);
                    }
                }
            }
            var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true);
            var cfg=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("rice-readonly",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),ds));
            try(var input=Files.newInputStream(root.resolve("mybatis-mapper/MenuMapper.xml"))) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,cfg,"mybatis-mapper/MenuMapper.xml",cfg.getSqlFragments()).parse();
            }
            try(var session=new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(cfg).openSession(c)) {
                var rows=session.<Map<String,Object>>selectList("com.example.demo.mapper.MenuMapper.AccountDetailList",Map.of("account_id","20250819193526","menu_id","TMENU001","servingQty",1));
                long started=System.nanoTime();
                var menus=session.<Map<String,Object>>selectList("com.example.demo.mapper.MenuMapper.AccountMenuList",Map.of("account_id","20250819193526"));
                System.out.println("COST_TIME_MS "+(System.nanoTime()-started)/1_000_000);
                System.out.println("COST_COUNTS total="+menus.size()+" complete="+menus.stream().filter(m->m.get("menu_cost_per_person")!=null).count());
                for(var m:menus) {var out=new LinkedHashMap<String,Object>();for(String key:List.of("menu_name","menu_cost_per_person","menu_cost_complete","menu_cost_reason"))out.put(key,m.get(key));System.out.println("COST_MENU "+out);}
                for(var row:rows)System.out.println("MENU_DETAIL " + row);
                assertThat(rows).anySatisfy(row -> {
                    assertThat(row.get("ingredient_id")).isEqualTo("TIG001");
                    assertThat(row.get("product_name")).isEqualTo("K 홍국쌀");
                });
                session.rollback();
            }
        }
    }
}
