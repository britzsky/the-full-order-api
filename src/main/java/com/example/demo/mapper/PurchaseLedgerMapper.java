package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/**
 * 매입 관리(ASP, 2026-11-23, 본사 전용). 매입 = tb_goods_receipt_item 전체
 * (발주 입고 ORDER · 발주서 없이 산 DIRECT(UNMATCHED) · 재고 시작일 전 HISTORY).
 * 공급사 청구 줄(tb_supplier_bill_line)은 발주 입고 금액 대조에만 쓰고 합계에 더하지 않는다.
 */
@Mapper
public interface PurchaseLedgerMapper {
	/** 기간·조건별 매입 합계(group_by: supplier|account|month|day) */
	List<Map<String, Object>> summary(Map<String, Object> params);

	/** 조건 전체 합계 한 줄 */
	Map<String, Object> totals(Map<String, Object> params);

	List<Map<String, Object>> lines(Map<String, Object> params);

	long lineCount(Map<String, Object> params);

	/** 발주 품목별 공급사 청구 합계 */
	List<Map<String, Object>> billTotals(Map<String, Object> params);

	/** 발주 품목들의 입고 줄(대조 대상) */
	List<Map<String, Object>> orderReceiptItems(Map<String, Object> params);

	/** 대조 결과 반영: 금액(공급사 값)·과세 구분·상태 */
	int applyCheck(Map<String, Object> params);

	int refreshReceiptTotals(Map<String, Object> params);

	/** 기간 안 청구 줄이 있는 발주 품목(다시 대조용) */
	List<Long> billedOrderItems(Map<String, Object> params);

	/** 공급사별 단가 기준 확인: 발주 단가 × 청구 수량이 공급가와 맞는지, 합계(부가세 포함)와 맞는지 */
	List<Map<String, Object>> priceCheckOrders(Map<String, Object> params);

	/** 공급사별 단가 기준 확인: 구매 이력의 공급사 단가 × 수량 대 공급가/합계 */
	List<Map<String, Object>> priceCheckHistory(Map<String, Object> params);

	/** 발주 단가가 공급가·합계 어느 쪽과도 맞지 않는 청구 줄 예시 */
	List<Map<String, Object>> priceCheckSamples(Map<String, Object> params);

	/** 우리 발주 품목을 못 찾은 청구 줄 */
	List<Map<String, Object>> unlinkedBillLines(Map<String, Object> params);

	/** 공급사는 청구했는데 우리 입고가 없는 발주 품목 */
	List<Map<String, Object>> billedNotReceived(Map<String, Object> params);
}
