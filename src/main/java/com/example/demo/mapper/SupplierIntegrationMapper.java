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
	int refreshUnusedOurhomePack(Map<String, Object> params);
	int preserveOurhomeMassOption(Map<String, Object> params);

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

	int refreshAccountProductLead(Map<String, Object> params);

	int markSyncStarted(Map<String, Object> params);

	int markSyncSucceeded(Map<String, Object> params);

	int markSyncFailed(Map<String, Object> params);

	List<Map<String, Object>> scheduledCatalogTargets();

	List<Map<String, Object>> scheduledReceiptTargets();

	// 아워홈 동기화 빠른 경로: 기존 상태 일괄 조회, 변경 없는 판매조건 일괄 갱신, 가격·판매상태 묶음 저장
	List<Map<String, Object>> ourhomeSiteOffers(Map<String, Object> params);

	int touchOffers(Map<String, Object> params);

	int insertOfferPrices(Map<String, Object> params);

	int upsertAvailabilities(Map<String, Object> params);
}
