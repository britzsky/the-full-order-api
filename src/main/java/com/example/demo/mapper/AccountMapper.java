package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountMapper {
	
	String NowDateKey();
	List<Map<String, Object>> AccountList(Map<String, Object> paramMap);

	/*
	 * method : MealSlotList
	 * comment : 고객사별 식사구분 설정 조회
	 */
	List<Map<String, Object>> MealSlotList(Map<String, Object> paramMap);

	/*
	 * method : DeleteMealSlotSetting
	 * comment : 고객사의 기존 식사구분 설정 삭제
	 */
	int DeleteMealSlotSetting(Map<String, Object> paramMap);

	/*
	 * method : InsertMealSlotSetting
	 * comment : 고객사가 선택한 식사구분 설정 등록
	 */
	int InsertMealSlotSetting(Map<String, Object> paramMap);
}
