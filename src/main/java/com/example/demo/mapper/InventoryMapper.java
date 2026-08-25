package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventoryMapper {
	List<Map<String, Object>> AccountInventoryList(Map<String, Object> paramMap);
	// 본사 식자재 마스터 관리 화면에서 사용하는 목록/등록/수정 쿼리다.
	List<Map<String, Object>> ingredientMasterList(Map<String, Object> paramMap);
	int insertIngredientMaster(Map<String, Object> paramMap);
	int updateIngredientMaster(Map<String, Object> paramMap);
	Map<String, Object> inventoryForUpdate(Map<String, Object> paramMap);
	int insertInventory(Map<String, Object> paramMap);
	int updateInventory(Map<String, Object> paramMap);
	int updateAccountProduct(Map<String, Object> paramMap);
	int deleteInventory(Map<String, Object> paramMap);
	List<Map<String, Object>> movements(Map<String, Object> paramMap);
	int insertMovement(Map<String, Object> paramMap);
}
