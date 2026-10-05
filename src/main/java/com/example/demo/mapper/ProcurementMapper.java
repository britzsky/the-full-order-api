package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProcurementMapper {
	List<Map<String, Object>> mealPlanIngredientAnalysis(Map<String, Object> params);

	Map<String, Object> mealPlanSummary(Map<String, Object> params);
}
