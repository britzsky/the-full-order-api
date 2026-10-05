package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.example.demo.mapper.SupplierCatalogMapper;
import com.example.demo.mapper.InventoryMapper;

/** 실제 로컬 DB에서 이미지 INSERT/UPDATE 및 조회를 검증하고 항상 롤백한다. */
@EnabledIfEnvironmentVariable(named="RUN_LOCAL_DB_TESTS", matches="true")
class LocalProductImagesDatabaseTest {
    @Test void imageInsertUpdatePreserveAndClearRoundTrip() throws Exception {
        Path resources=Path.of("src/main/resources");
        Properties props=new Properties();
        for(String file:List.of("application.properties","application-secret.properties","application-local.properties")) {
            try(var reader=Files.newBufferedReader(resources.resolve(file))){props.load(reader);}
        }
        String url=props.getProperty("spring.datasource.url");
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
        var ds=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url,props.getProperty("spring.datasource.username"),props.getProperty("spring.datasource.password"));
        var cfg=new Configuration(new Environment("image-rollback",new JdbcTransactionFactory(),ds));
        for(String name:List.of("InventoryMapper","SupplierCatalogMapper","SupplierIntegrationMapper")) {
            String resource="mybatis-mapper/"+name+".xml";
            try(var stream=Files.newInputStream(resources.resolve(resource))){new XMLMapperBuilder(stream,cfg,resource,cfg.getSqlFragments()).parse();}
        }
        try(SqlSession session=new SqlSessionFactoryBuilder().build(cfg).openSession(false)) {
            try {
                String id="IMG"+UUID.randomUUID().toString().replace("-","").substring(0,12);
                var catalog=session.getMapper(SupplierCatalogMapper.class);
                var inventory=session.getMapper(InventoryMapper.class);
                var integration=session.getMapper(SupplierIntegrationMapper.class);
                var supplier=new HashMap<String,Object>(Map.of("supplier_code",id,"supplier_name","사진 롤백 검증"));
                new SupplierCatalogService(catalog).createSupplier(supplier);
                var ingredient=new HashMap<String,Object>(Map.of("ingredient_id",id,"ingredient_name","사진 검증","base_unit","g","order_unit","BOX","convert_value",1000));
                new InventoryService(inventory, org.mockito.Mockito.mock(StockAvailabilityService.class)).createIngredientMaster(ingredient);
                Map<String,Object> product=new HashMap<>(Map.of("supplier_id",supplier.get("supplier_id"),"ingredient_id",id,"supplier_item_code",id,
                    "product_name","사진 검증","order_unit","BOX","package_qty",1000,"package_unit","g","base_qty",1000,"base_unit","g","active_yn","Y"));
                product.put("minimum_order_qty",1); product.put("order_increment_qty",1);
                product.put("images_provided",true); product.put("image_url","https://img.example.com/a.jpg"); product.put("thumbnail_url","https://img.example.com/t.jpg");
                integration.upsertSupplierProduct(product);
                assertThat(catalog.products(Map.of("ingredient_id",id)).get(0)).containsEntry("image_url",product.get("image_url")).containsEntry("thumbnail_url",product.get("thumbnail_url"));
                product.put("supplier_product_id",integration.supplierProductId(product));
                product.put("purchase_price",1000);
                new SupplierCatalogService(catalog).updateProductWithPrice(product);
                assertThat(inventory.ingredientMasterList(Map.of("account_id","IMAGE_TEST","ingredient_id",id)).get(0))
                    .containsEntry("image_url","https://img.example.com/a.jpg").containsEntry("thumbnail_url","https://img.example.com/t.jpg");
                product.put("image_url","https://img.example.com/b.jpg");
                integration.upsertSupplierProduct(product);
                assertThat(catalog.products(Map.of("ingredient_id",id)).get(0)).containsEntry("image_url","https://img.example.com/b.jpg");
                product.put("images_provided",false);product.put("image_url",null);product.put("thumbnail_url",null);
                integration.upsertSupplierProduct(product);
                assertThat(catalog.products(Map.of("ingredient_id",id)).get(0)).containsEntry("image_url","https://img.example.com/b.jpg");
                product.put("images_provided",true);integration.upsertSupplierProduct(product);
                assertThat(catalog.products(Map.of("ingredient_id",id)).get(0).get("image_url")).isNull();
                assertThat(catalog.products(Map.of("ingredient_id",id)).get(0).get("thumbnail_url")).isNull();
            } finally { session.rollback(); }
        }
    }
}
