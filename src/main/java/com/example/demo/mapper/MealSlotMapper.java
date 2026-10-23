package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** 본사 끼니 종류(tb_meal_slot_master) 관리. 거래처별 선택은 AccountMapper.MealSlotList/MealSlotSave */
@Mapper
public interface MealSlotMapper {
	@Select("""
		SELECT m.meal_slot_code,m.meal_slot_name,m.slot_kind,m.display_order,m.active_yn,
		       (SELECT COUNT(*) FROM the_full_order.tb_account_meal_slot_setting s WHERE s.meal_slot_code=m.meal_slot_code) account_count
		  FROM the_full_order.tb_meal_slot_master m
		 ORDER BY m.display_order,m.meal_slot_code""")
	List<Map<String, Object>> list();

	@Select("SELECT COALESCE(MAX(meal_slot_code),8)+1 FROM the_full_order.tb_meal_slot_master")
	int nextCode();

	@Select("SELECT COALESCE(MAX(display_order),-1)+1 FROM the_full_order.tb_meal_slot_master")
	int nextOrder();

	@Select("SELECT COUNT(*) FROM the_full_order.tb_meal_slot_master WHERE meal_slot_name=#{meal_slot_name} AND meal_slot_code<>#{meal_slot_code}")
	int sameName(Map<String, Object> p);

	@Select("SELECT COUNT(*) FROM the_full_order.tb_meal_slot_master WHERE meal_slot_code=#{meal_slot_code}")
	int exists(Map<String, Object> p);

	@Insert("""
		INSERT INTO the_full_order.tb_meal_slot_master(meal_slot_code,meal_slot_name,slot_kind,display_order,active_yn,user_id)
		VALUES(#{meal_slot_code},#{meal_slot_name},#{slot_kind},#{display_order},'Y',#{user_id})""")
	int insert(Map<String, Object> p);

	@Update("""
		UPDATE the_full_order.tb_meal_slot_master
		   SET meal_slot_name=#{meal_slot_name},slot_kind=#{slot_kind},active_yn=#{active_yn},user_id=#{user_id}
		 WHERE meal_slot_code=#{meal_slot_code}""")
	int update(Map<String, Object> p);

	@Update("UPDATE the_full_order.tb_meal_slot_master SET display_order=#{display_order},user_id=#{user_id} WHERE meal_slot_code=#{meal_slot_code}")
	int updateOrder(Map<String, Object> p);
}
