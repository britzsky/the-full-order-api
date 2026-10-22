package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 본사 금액대 식단표 템플릿(금액대·식단유형별 최대 3개, 본사 메뉴) */
@Mapper
public interface MealPlanTemplateMapper {
	List<Map<String, Object>> templates(Map<String, Object> p);

	List<Map<String, Object>> items(Map<String, Object> p);

	int insertTemplate(Map<String, Object> p);

	int updateTemplate(Map<String, Object> p);

	int deleteItems(Map<String, Object> p);

	int insertItem(Map<String, Object> p);

	int deleteTemplate(Map<String, Object> p);

	int activeMenuCount(Map<String, Object> p);

	Map<String, Object> accountDailyBudget(Map<String, Object> p);
}
