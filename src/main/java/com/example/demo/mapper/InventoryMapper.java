package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventoryMapper {
	// 거래처 재고: 거래처 식재료 단위(공급사 상품과 연결하지 않는다)
	List<Map<String, Object>> AccountInventoryList(Map<String, Object> paramMap);

	// 본사 식자재 마스터 관리 화면에서 사용하는 목록/등록/수정 쿼리다(표준 단가 포함).
	List<Map<String, Object>> ingredientMasterList(Map<String, Object> paramMap);

	int insertIngredientMaster(Map<String, Object> paramMap);

	int updateIngredientMaster(Map<String, Object> paramMap);

	Map<String, Object> accountIngredient(Map<String, Object> paramMap);

	int ensureBalance(Map<String, Object> paramMap);

	Long balanceId(Map<String, Object> paramMap);

	int hasNonAdjustMovement(Map<String, Object> paramMap);

	// 기초재고 추정 단가(직접 입력·최근 입고·본사 표준). inventory_balance_id를 주면 한 행만.
	List<Map<String, Object>> openingCostEstimates(Map<String, Object> paramMap);

	int updateInventoryValue(Map<String, Object> paramMap);

	int insertValuedMovement(Map<String, Object> paramMap);

	Map<String, Object> inventoryForUpdate(Map<String, Object> paramMap);

	int updateInventory(Map<String, Object> paramMap);

	int updateIngredientStockSettings(Map<String, Object> paramMap);

	int deleteInventory(Map<String, Object> paramMap);

	List<Map<String, Object>> movements(Map<String, Object> paramMap);

	List<Map<String, Object>> movementSummary(Map<String, Object> paramMap);

	int insertMovement(Map<String, Object> paramMap);
}
