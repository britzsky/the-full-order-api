package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/**
 * 직접구매(마트·쿠팡 등). "직접구매" 공급사(supplier_type=DIRECT) 아래 거래처 자체 상품을 만들고,
 * 소유는 그 상품의 유일한 거래처 연결(tb_account_ingredient_product)로 판단한다. 스키마 변경 없음.
 */
@Mapper
public interface DirectPurchaseMapper {
    String DIRECT_SUPPLIER_CODE = "DIRECT";

    /** 직접구매 공급사 행을 처음 사용할 때 만든다 (supplier_code 유니크). */
    @Insert("INSERT IGNORE INTO the_full_order.tb_supplier(supplier_code,supplier_name,supplier_type,active_yn,user_id) VALUES('DIRECT','직접구매','DIRECT','Y','SYSTEM')")
    int ensureDirectSupplier();

    @Select("SELECT supplier_id FROM the_full_order.tb_supplier WHERE supplier_code='DIRECT'")
    Long directSupplierId();

    /** 같은 요청키로 이미 기록한 직접구매 (재시도 응답 복구용) */
    @Select("SELECT m.movement_id,m.account_ingredient_product_id,m.quantity_delta,m.amount_delta,m.unit_cost_snapshot,m.base_unit,p.supplier_product_id,p.product_name FROM the_full_order.tb_account_inventory_movement m JOIN the_full_order.tb_account_ingredient_product ap ON ap.account_ingredient_product_id=m.account_ingredient_product_id JOIN the_full_order.tb_supplier_ingredient_product p ON p.supplier_product_id=ap.supplier_product_id WHERE m.account_id=#{account_id} AND m.reference_type='DIRECT_PURCHASE' AND m.reference_id=#{request_key} LIMIT 1")
    Map<String,Object> recorded(Map<String,Object> params);

    /** 이 거래처가 이 식재료에 연결해 둔 직접구매 상품들 */
    @Select("SELECT ap.account_ingredient_product_id,ap.supplier_product_id,ap.mapped_ingredient_id,ap.active_yn,ap.preferred_yn,ap.default_location_id,COALESCE(ap.link_base_unit,p.base_unit) base_unit,p.product_name FROM the_full_order.tb_account_ingredient_product ap JOIN the_full_order.tb_supplier_ingredient_product p ON p.supplier_product_id=ap.supplier_product_id JOIN the_full_order.tb_supplier s ON s.supplier_id=p.supplier_id WHERE ap.account_id=#{account_id} AND ap.mapped_ingredient_id=#{ingredient_id} AND s.supplier_code='DIRECT' ORDER BY ap.active_yn DESC,ap.account_ingredient_product_id")
    List<Map<String,Object>> directLinks(Map<String,Object> params);

    /** 같은 식재료에 우선 상품이 이미 있는지 (직접구매 상품은 다른 우선 상품을 밀어내지 않는다) */
    @Select("SELECT COUNT(*) FROM the_full_order.tb_account_ingredient_product WHERE account_id=#{account_id} AND mapped_ingredient_id=#{ingredient_id} AND active_yn='Y' AND preferred_yn='Y'")
    int preferredCount(Map<String,Object> params);

    @Insert("INSERT INTO the_full_order.tb_supplier_ingredient_product(supplier_id,ingredient_id,supplier_item_code,product_name,order_unit,package_qty,package_unit,base_qty,base_unit,minimum_order_qty,order_multiple_qty,active_yn,user_id) VALUES(#{supplier_id},NULL,#{supplier_item_code},#{product_name},#{base_unit},1,#{base_unit},1,#{base_unit},1,1,'Y',#{user_id})")
    @Options(useGeneratedKeys=true,keyProperty="supplier_product_id",keyColumn="supplier_product_id")
    int insertProduct(Map<String,Object> params);

    @Insert("INSERT INTO the_full_order.tb_account_ingredient_product(account_id,supplier_product_id,mapped_ingredient_id,link_base_qty,link_base_unit,preferred_yn,default_location_id,active_yn,user_id) VALUES(#{account_id},#{supplier_product_id},#{ingredient_id},1,#{base_unit},#{preferred_yn},'L999','Y',#{user_id})")
    @Options(useGeneratedKeys=true,keyProperty="account_ingredient_product_id",keyColumn="account_ingredient_product_id")
    int insertLink(Map<String,Object> params);

    /** 직접구매 입고 이력. 근거는 DIRECT_PURCHASE + 요청키 (이력 유니크 키로 중복 반영 차단) */
    @Insert("INSERT INTO the_full_order.tb_account_inventory_movement(movement_id,account_id,account_ingredient_product_id,location_id,movement_type,quantity_delta,quantity_before,quantity_after,unit_cost_snapshot,amount_delta,average_unit_cost_before,average_unit_cost_after,base_unit,reference_type,reference_id,movement_at,user_id) VALUES(#{movement_id},#{account_id},#{account_ingredient_product_id},#{location_id},'IN',#{base_received_qty},#{quantity_before},#{quantity_after},#{receipt_base_unit_cost},#{receipt_amount},#{average_unit_cost_before},#{average_unit_cost_after},#{base_unit},'DIRECT_PURCHASE',#{request_key},CASE WHEN #{receipt_date}=CURRENT_DATE THEN NOW() ELSE CAST(#{receipt_date} AS DATETIME) END,#{user_id})")
    int insertMovement(Map<String,Object> params);
}
