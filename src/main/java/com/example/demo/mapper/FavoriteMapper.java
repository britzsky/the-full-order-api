package com.example.demo.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 나만의 식재료(웰스토리·아워홈 상품 하트). 주인은 현장=거래처(ACCOUNT), 본사=사용자(USER). 해제하면 행 삭제. */
@Mapper
public interface FavoriteMapper {
    @Select("SELECT COUNT(*) FROM the_full_order.tb_supplier_ingredient_product WHERE supplier_product_id=#{supplier_product_id}")
    int productExists(Map<String, Object> params);

    @Insert("INSERT IGNORE INTO the_full_order.tb_favorite_product(owner_type,owner_id,supplier_product_id,user_id) VALUES(#{favorite_owner_type},#{favorite_owner_id},#{supplier_product_id},#{user_id})")
    int addProduct(Map<String, Object> params);

    @Delete("DELETE FROM the_full_order.tb_favorite_product WHERE owner_type=#{favorite_owner_type} AND owner_id=#{favorite_owner_id} AND supplier_product_id=#{supplier_product_id}")
    int removeProduct(Map<String, Object> params);
}
