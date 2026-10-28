package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

/** 식단표 부속 서류 조회(조리계획표·급식운영일지·보존식 라벨) */
@Mapper
public interface MealDocumentMapper {
	Map<String, Object> plan(Map<String, Object> p);

	List<Map<String, Object>> slots(Map<String, Object> p);

	List<Map<String, Object>> services(Map<String, Object> p);

	List<Map<String, Object>> menus(Map<String, Object> p);

	List<Map<String, Object>> requirements(Map<String, Object> p);
}
