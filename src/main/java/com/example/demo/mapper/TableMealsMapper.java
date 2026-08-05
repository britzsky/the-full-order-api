package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TableMealsMapper {
	
	String NowDateKey();
	List<Map<String, Object>> TableMealsList(Map<String, Object> paramMap);
	List<Map<String, Object>> TableMealsDetailList(Map<String, Object> paramMap);
	int TableMealsHeaderSave(Map<String, Object> paramMap);
	int TableMealsDetailDelete(Map<String, Object> paramMap);
	int TableMealsDetailSave(Map<String, Object> paramMap);
}
