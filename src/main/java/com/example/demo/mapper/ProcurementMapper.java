package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProcurementMapper {
	List<Map<String, Object>> mealPlanIngredientAnalysis(Map<String, Object> params);
	Map<String, Object> mealPlanSummary(Map<String, Object> params);
	List<Map<String, Object>> purchaseOrders(Map<String, Object> params);
	List<Map<String, Object>> purchaseOrderItems(Map<String, Object> params);
	int insertPurchaseOrder(Map<String, Object> params);
	int insertPurchaseOrderItem(Map<String, Object> params);
	int updatePurchaseOrderStatus(Map<String, Object> params);
	int updatePurchaseOrderItemReconciliation(Map<String, Object> params);
	int markItemReceived(Map<String, Object> params);
	int increaseInventory(Map<String, Object> params);
	int insertInventoryMovement(Map<String, Object> params);
}
