package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/**
 * 공급사 구매 이력(웰스토리 입고내역·아워홈 주문 입고 내역) 가져오기와 단가 이력(2026-11-19).
 * 저장은 기존 tb_goods_receipt(_item)에 발주서 없이(purchase_order_id NULL) 한다.
 * 재고 시작일 전 = status HISTORY, 후 = UNMATCHED. 둘 다 재고에 넣지 않는다(inventory_movement_id NULL).
 */
@Mapper
public interface PurchaseHistoryMapper {
	String inventoryStartDate(Map<String, Object> params);

	int saveInventorySetting(Map<String, Object> params);

	/** 재고 시작일이 바뀌면 이미 가져온 이력(HISTORY/UNMATCHED)을 다시 나눈다 */
	int reclassifyHistory(Map<String, Object> params);

	/** 거래처에 연결된 사용 중 웰스토리·아워홈 사업장 */
	List<Map<String, Object>> importSites(Map<String, Object> params);

	/** 우리 발주서(외부 주문번호 = client_ord)가 있는 주문인지. 있으면 발주서 입고 흐름이 맡으므로 이력으로 넣지 않는다 */
	int purchaseOrderCount(Map<String, Object> params);

	Map<String, Object> productForHistory(Map<String, Object> params);

	/** 상품 마스터에 없는 상품(단종 등)을 입고 내역의 이름·규격으로 등록. 판매 중 상품이 아니므로 active_yn=N */
	int insertHistoryProduct(Map<String, Object> params);

	int upsertHistoryReceipt(Map<String, Object> params);

	int upsertHistoryReceiptItem(Map<String, Object> params);

	int refreshHistoryReceiptTotals(Map<String, Object> params);

	/** ASP(2026-11-23): 공급사 청구 줄의 주문번호+순번으로 우리 발주 품목 찾기 */
	Map<String, Object> orderItemForBill(Map<String, Object> params);

	/** ASP: 우리 발주 주문의 공급사 청구 줄 저장(같은 줄은 덮어씀 — 반품·정정 반영) */
	int upsertBillLine(Map<String, Object> params);

	/** 구매 이력에서 이미 식재료를 고른 상품(상품별 가장 최근 연결) */
	List<Map<String, Object>> knownLinks(Map<String, Object> params);

	/** 재고에 넣지 않은 이력 줄의 식재료·기준수량을 바꾼다. only_unlinked=true면 아직 식재료가 없는 줄만 */
	int linkProductLines(Map<String, Object> params);

	Map<String, Object> accountIngredient(Map<String, Object> params);

	Map<String, Object> product(Map<String, Object> params);

	/** 기간 중 산 상품 목록(금액 큰 순) — 식재료 연결·실사표·ABC 분류용 */
	List<Map<String, Object>> purchasedProducts(Map<String, Object> params);

	/** 식재료별 기준단위당 단가 요약(가중평균·최저·최고) */
	List<Map<String, Object>> ingredientPrices(Map<String, Object> params);

	/** 식재료 한 개의 입고 줄별 단가 */
	List<Map<String, Object>> ingredientPriceLines(Map<String, Object> params);
}
