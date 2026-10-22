package com.example.demo.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 직접구매(마트·쿠팡 등). 공급사 상품을 만들지 않고 거래처 식재료 재고에 바로 넣는다(2026-10-22).
 * 산 물건 이름은 LOT·이력의 product_name_snapshot으로 남긴다.
 */
@Mapper
public interface DirectPurchaseMapper {
    /** 거래처 식재료 행을 잠그고 기준단위를 돌려준다(같은 식재료의 동시 기록·같은 요청 재시도를 직렬화). */
    @Select("SELECT base_unit FROM the_full_order.tb_account_ingredient_master WHERE account_id=#{account_id} AND ingredient_id=#{ingredient_id} FOR UPDATE")
    String lockAccountIngredientUnit(Map<String,Object> params);

    /** 같은 요청키로 이미 기록한 직접구매 (재시도 응답 복구용) */
    @Select("SELECT m.movement_id,m.ingredient_id,m.quantity_delta,m.amount_delta,m.unit_cost_snapshot,m.base_unit,m.product_name_snapshot product_name FROM the_full_order.tb_account_inventory_movement m WHERE m.account_id=#{account_id} AND m.reference_type='DIRECT_PURCHASE' AND m.reference_id=#{request_key} LIMIT 1")
    Map<String,Object> recorded(Map<String,Object> params);

    /** 직접구매 입고 이력. 근거는 DIRECT_PURCHASE + 요청키 (이력 유니크 키로 중복 반영 차단) */
    @Insert("INSERT INTO the_full_order.tb_account_inventory_movement(movement_id,account_id,ingredient_id,product_name_snapshot,location_id,movement_type,quantity_delta,quantity_before,quantity_after,unit_cost_snapshot,amount_delta,average_unit_cost_before,average_unit_cost_after,base_unit,reference_type,reference_id,movement_at,user_id) VALUES(#{movement_id},#{account_id},#{ingredient_id},#{product_name},#{location_id},'IN',#{base_received_qty},#{quantity_before},#{quantity_after},#{receipt_base_unit_cost},#{receipt_amount},#{average_unit_cost_before},#{average_unit_cost_after},#{base_unit},'DIRECT_PURCHASE',#{request_key},CASE WHEN #{receipt_date}=CURRENT_DATE THEN NOW() ELSE CAST(#{receipt_date} AS DATETIME) END,#{user_id})")
    int insertMovement(Map<String,Object> params);
}
