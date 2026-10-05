package com.example.demo.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface IngredientLinkMapper {
    /** 거래처 식재료의 기준단위 (본사 식자재 마스터가 아니라 거래처 식재료 기준). 행을 잠가 동시 연결을 직렬화한다. */
    @Select("SELECT base_unit FROM the_full_order.tb_account_ingredient_master WHERE account_id=#{account_id} AND ingredient_id=#{ingredient_id} FOR UPDATE")
    String lockAccountIngredientUnit(Map<String,Object> params);

    /** 이 연결 상품에 0이 아닌 재고가 남아 있는 단위들 */
    @Select("SELECT DISTINCT b.base_unit FROM the_full_order.tb_account_inventory_balance b JOIN the_full_order.tb_account_ingredient_product ap ON ap.account_ingredient_product_id=b.account_ingredient_product_id WHERE ap.account_id=#{account_id} AND ap.supplier_product_id=#{supplier_product_id} AND b.current_base_qty<>0")
    java.util.List<String> stockUnits(Map<String,Object> params);
    @Update("UPDATE the_full_order.tb_account_ingredient_product ap SET ap.preferred_yn='N',ap.mod_at=NOW() WHERE ap.account_id=#{account_id} AND ap.mapped_ingredient_id=#{ingredient_id}")
    int clearPreferred(Map<String,Object> params);

    @Update("UPDATE the_full_order.tb_account_ingredient_product SET mapped_ingredient_id=#{ingredient_id},link_base_qty=#{link_base_qty},link_base_unit=#{link_base_unit} WHERE account_id=#{account_id} AND supplier_product_id=#{supplier_product_id}")
    int setIngredient(Map<String,Object> params);

    @Insert("INSERT IGNORE INTO the_full_order.tb_account_inventory_balance(account_id,account_ingredient_product_id,location_id,current_base_qty,base_unit,user_id) SELECT ap.account_id,ap.account_ingredient_product_id,COALESCE(ap.default_location_id,'L999'),0,COALESCE(ap.link_base_unit,p.base_unit),#{user_id} FROM the_full_order.tb_account_ingredient_product ap JOIN the_full_order.tb_supplier_ingredient_product p ON p.supplier_product_id=ap.supplier_product_id WHERE ap.account_id=#{account_id} AND ap.supplier_product_id=#{supplier_product_id}")
    int ensureInventory(Map<String,Object> params);
    @Select("SELECT ap.* FROM the_full_order.tb_account_ingredient_product ap WHERE ap.account_id=#{account_id} AND ap.account_ingredient_product_id=#{account_ingredient_product_id} FOR UPDATE")
    Map<String,Object> lockLink(Map<String,Object> params);

    @Select("SELECT ap.* FROM the_full_order.tb_account_ingredient_product ap WHERE ap.account_id=#{account_id} AND ap.supplier_product_id=#{supplier_product_id} FOR UPDATE")
    Map<String,Object> lockProductLink(Map<String,Object> params);

    @Select("SELECT o.supplier_product_id FROM the_full_order.tb_supplier_site_product_offer o JOIN the_full_order.tb_supplier_account_site ss ON ss.supplier_account_site_id=o.supplier_account_site_id WHERE ss.account_id=#{account_id} AND ss.active_yn='Y' AND o.active_yn='Y' AND o.supplier_offer_id=#{supplier_offer_id}")
    Long offerProduct(Map<String,Object> params);
}
