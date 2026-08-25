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

	/* 고객사 유형별 계산 단가와 식사구분별 기본 구성을 조회한다. */
	List<Map<String, Object>> MealConfiguration(Map<String, Object> paramMap);

	/* 식사구분 설정 삭제 전에 고객사별 기본 프로필을 명시적으로 제거한다. */
	int DeleteMealSlotProfile(Map<String, Object> paramMap);

	/* 선택된 식사구분별 식사분류·식단유형·구성 템플릿을 등록한다. */
	int InsertMealSlotProfile(Map<String, Object> paramMap);
}
