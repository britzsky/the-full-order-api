package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventoryMapper {
	List<Map<String, Object>> AccountInventoryList(Map<String, Object> paramMap);
	Map<String, Object> inventoryForUpdate(Map<String, Object> paramMap);
	int insertInventory(Map<String, Object> paramMap);
	int updateInventory(Map<String, Object> paramMap);
	int updateAccountProduct(Map<String, Object> paramMap);
	int deleteInventory(Map<String, Object> paramMap);
	List<Map<String, Object>> movements(Map<String, Object> paramMap);
	int insertMovement(Map<String, Object> paramMap);
}
