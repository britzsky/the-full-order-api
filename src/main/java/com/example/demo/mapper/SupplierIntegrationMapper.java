package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierIntegrationMapper {
    int invalidateOurhomeAvailability(Map<String,Object> params);
	List<Map<String, Object>> sites(Map<String, Object> params);

	List<Map<String, Object>> offers(Map<String, Object> params);

	List<Map<String, Object>> syncStatus(Map<String, Object> params);

	List<Map<String, Object>> receipts(Map<String, Object> params);

	List<Map<String, Object>> incidents(Map<String, Object> params);

	int upsertOffer(Map<String, Object> params);

	int closeOfferPrice(Map<String, Object> params);

	int insertOfferPrice(Map<String, Object> params);

	int upsertAvailability(Map<String, Object> params);

	int ensureSupplier(Map<String, Object> params);

	Long supplierIdByCode(Map<String, Object> params);

	int upsertSupplierProduct(Map<String, Object> params);

	Map<String, Object> orderLineForReceipt(Map<String, Object> params);

	int insertGoodsReceipt(Map<String, Object> params);

	int insertGoodsReceiptItem(Map<String, Object> params);

	Map<String, Object> inventoryBalanceForUpdate(Map<String, Object> params);

	int upsertInventoryAtAverageCost(Map<String, Object> params);

	int insertInventoryLot(Map<String, Object> params);

	int insertCostedInventoryMovement(Map<String, Object> params);

	int markReceiptItemPosted(Map<String, Object> params);

	int addReceivedOrderQty(Map<String, Object> params);

	int refreshPurchaseOrderReceiptStatus(Map<String, Object> params);

	Long supplierProductId(Map<String, Object> params);

	int upsertAccountProduct(Map<String, Object> params);

	int markSyncStarted(Map<String, Object> params);

	int markSyncSucceeded(Map<String, Object> params);

	int markSyncFailed(Map<String, Object> params);

	List<Map<String, Object>> scheduledCatalogTargets();

	List<Map<String, Object>> scheduledReceiptTargets();
}
