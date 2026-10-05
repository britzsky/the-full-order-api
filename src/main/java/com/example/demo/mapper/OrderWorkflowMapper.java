package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 발주 전송 전 저장, 결과 대사와 입고 잠금에 사용하는 현재 스키마 매퍼. */
@Mapper
public interface OrderWorkflowMapper {
	Map<String, Object> lockSourceIngredient(Map<String, Object> params);
	List<Map<String, Object>> sourceLinks(Map<String, Object> params);
	List<Map<String, Object>> linkContext(Map<String, Object> params);
	Map<String, Object> lockAccountProduct(Map<String, Object> params);
	int ensureAccountProduct(Map<String, Object> params);
	int linkReceivedProduct(Map<String, Object> params);
	int updateLinkStatus(Map<String, Object> params);
	Map<String, Object> order(Map<String, Object> params);

	Map<String, Object> lockOrder(Map<String, Object> params);

	List<Map<String, Object>> orders(Map<String, Object> params);

	List<Map<String, Object>> items(Map<String, Object> params);

	int insertOrder(Map<String, Object> params);

	int insertItem(Map<String, Object> params);

	int updateOrder(Map<String, Object> params);

	int updateItem(Map<String, Object> params);

	int insertExchange(Map<String, Object> params);

	int finishExchange(Map<String, Object> params);
    int insertReconciliation(Map<String,Object> params);

	int ensureBalance(Map<String, Object> params);

	Map<String, Object> receiptItem(Map<String, Object> params);

	int refreshReceipt(Map<String, Object> params);

	int manualReceiptCount(Map<String, Object> params);

	Long lockSupplier(Map<String, Object> params);

	List<String> clientOrderKeys(Map<String, Object> params);
}
