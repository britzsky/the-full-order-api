package com.example.demo.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.TableMealsMapper;

@Service
public class TableMealsService {

	TableMealsMapper tableMealsMapper;
	
	public TableMealsService(TableMealsMapper tableMealsMapper) {
		this.tableMealsMapper = tableMealsMapper;
	}
	
	public List<Map<String, Object>> TableMealsList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = tableMealsMapper.TableMealsList(paramMap);
		return resultList;
	}
	
	public List<Map<String, Object>> TableMealsDetailList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = tableMealsMapper.TableMealsDetailList(paramMap);
		return resultList;
	}
	
	@Transactional
	public Map<String, Object> TableMealsSave(Map<String, Object> paramMap) {
		Map<String, Object> resultMap = new HashMap<>();
		
		try {
			if (isBlank(paramMap.get("account_id"))) {
				return response(400, "account_id is required.", null, 0);
			}
			
			if (isBlank(paramMap.get("table_year")) || isBlank(paramMap.get("table_month")) || isBlank(paramMap.get("table_week"))) {
				return response(400, "table_year, table_month, table_week are required.", null, 0);
			}
			
			paramMap.put("table_id", tableId(paramMap));
			List<Map<String, Object>> detailList = tableMeals(paramMap);
			
			if (detailList.isEmpty()) {
				return response(400, "table_meals or meals is required.", paramMap.get("table_id"), 0);
			}
			
			int result = tableMealsMapper.TableMealsHeaderSave(paramMap);
			tableMealsMapper.TableMealsDetailDelete(paramMap);
			
			for (Map<String, Object> detail : detailList) {
				detail.put("table_id", paramMap.get("table_id"));
				detail.put("account_id", paramMap.get("account_id"));
				detail.put("user_id", paramMap.get("user_id"));
				result += tableMealsMapper.TableMealsDetailSave(detail);
			}
			
			resultMap.put("code", result > 0 ? 200 : 400);
			resultMap.put("message", result > 0 ? "success" : "fail");
			resultMap.put("table_id", paramMap.get("table_id"));
			resultMap.put("saved_count", detailList.size());
			return resultMap;
		} catch (Exception e) {
			return response(500, e.getMessage(), paramMap.get("table_id"), 0);
		}
	}
	
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> tableMeals(Map<String, Object> paramMap) {
		Object tableMeals = paramMap.get("table_meals");
		if (tableMeals instanceof List<?>) {
			return (List<Map<String, Object>>) tableMeals;
		}
		
		List<Map<String, Object>> resultList = new ArrayList<>();
		Object meals = paramMap.get("meals");
		if (!(meals instanceof List<?> mealList)) {
			return resultList;
		}
		
		for (Object mealValue : mealList) {
			if (!(mealValue instanceof Map<?, ?> meal)) {
				continue;
			}
			
			Object menus = meal.get("menus");
			if (!(menus instanceof List<?> menuList)) {
				continue;
			}
			
			for (Object menuValue : menuList) {
				if (!(menuValue instanceof Map<?, ?> menu)) {
					continue;
				}
				
				Map<String, Object> detail = new HashMap<>();
				copy(detail, meal, "meal_date", "weekday", "meal_slot");
				copy(detail, menu, "sort_order", "menu_id", "menu_name", "meal_category", "menu_type", "menu_gubun");
				resultList.add(detail);
			}
		}
		
		return resultList;
	}
	
	private void copy(Map<String, Object> target, Map<?, ?> source, String... keys) {
		for (String key : keys) {
			target.put(key, source.get(key));
		}
	}
	
	private String tableId(Map<String, Object> paramMap) {
		if (!isBlank(paramMap.get("table_id"))) {
			return paramMap.get("table_id").toString();
		}
		
		String source = isBlank(paramMap.get("source")) ? "manual" : paramMap.get("source").toString();
		return paramMap.get("account_id") + "_" + paramMap.get("table_year") + "_"
				+ paramMap.get("table_month") + "_" + paramMap.get("table_week") + "_" + source;
	}
	
	private Map<String, Object> response(int code, String message, Object tableId, int savedCount) {
		Map<String, Object> resultMap = new HashMap<>();
		resultMap.put("code", code);
		resultMap.put("message", message);
		resultMap.put("table_id", tableId);
		resultMap.put("saved_count", savedCount);
		return resultMap;
	}
	
	private boolean isBlank(Object value) {
		return value == null || value.toString().isBlank();
	}
}
