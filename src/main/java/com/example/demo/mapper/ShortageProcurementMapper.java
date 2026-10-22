package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShortageProcurementMapper {
	List<Map<String, Object>> demand(Map<String, Object> params);

	List<Map<String, Object>> unconfirmedUsage(Map<String, Object> params);

	List<Map<String, Object>> draftDemand(Map<String, Object> params);

	List<Map<String, Object>> stock(Map<String, Object> params);

	List<Map<String, Object>> targets(Map<String, Object> params);

	List<Map<String, Object>> history(Map<String, Object> params);

	List<Map<String, Object>> incoming(Map<String, Object> params);

	// 나만의 메뉴에 쓰인 식재료(favorite_owner_type/favorite_owner_id)
	List<Map<String, Object>> favoriteMenuIngredients(Map<String, Object> params);
}
