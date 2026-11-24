package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.example.demo.mapper.PurchaseHistoryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 구매 이력 가져오기: 공급사 응답 → 입고 테이블(발주서 없이, 재고 미반영), 재고 시작일 기준 HISTORY/UNMATCHED */
class PurchaseHistoryImportServiceTest {
	final ObjectMapper json = new ObjectMapper();
	final PurchaseHistoryMapper mapper = mock(PurchaseHistoryMapper.class);
	final WelstoryItemLookupService welstory = mock(WelstoryItemLookupService.class);
	final OurhomeSiteService ourhome = mock(OurhomeSiteService.class);
	final PurchaseHistoryImportService service = new PurchaseHistoryImportService(mapper, welstory, ourhome, json, 0);
	final LocalDate day = LocalDate.now().minusDays(3);

	Map<String, Object> site(String supplier, long supplierId) {
		return new HashMap<>(Map.of("supplier_account_site_id", 7L, "supplier_id", supplierId, "external_site_code",
				supplier.equals("WELSTORY") ? "1000123" : "FU0UP", "supplier_code", supplier));
	}

	@BeforeEach
	void products() {
		when(mapper.productForHistory(anyMap())).thenAnswer(i -> {
			Map<?, ?> k = i.getArgument(0);
			return new HashMap<>(Map.of("supplier_product_id", 100L, "base_qty", new BigDecimal("1000"), "base_unit", "g",
					"supplier_item_code", k.get("supplier_item_code")));
		});
	}

	Map<String, Object> command() {
		return new HashMap<>(Map.of("account_id", "A1", "from_date", day.toString(), "to_date", day.toString(), "user_id", "HQ"));
	}

	JsonNode welstoryResponse(String rows) throws Exception {
		return json.readTree("{\"dataHeader\":{},\"dataBody\":{\"resCd\":\"S0000\",\"data\":" + rows + "}}");
	}

	@Test
	void welstoryReceiptsBecomeHistoryWithoutOurOrdersAndZeroLines() throws Exception {
		when(mapper.importSites(anyMap())).thenReturn(List.of(site("WELSTORY", 1L)));
		when(mapper.inventoryStartDate(anyMap())).thenReturn(null);
		// 우리 발주서가 있는 주문번호
		when(mapper.purchaseOrderCount(argThat(p -> "OURORDER1".equals(p.get("client_ord"))))).thenReturn(1);
		String d = day.toString().replace("-", "");
		when(welstory.call(eq("/fdapi/service/payer-receive-detail"), any())).thenReturn(welstoryResponse("["
				+ "{\"orderType\":\"YFOS\",\"clientOrd\":\"W1\",\"clientOrdItem\":\"000001\",\"billDate\":\"" + d + "\",\"itemCode\":\"111\",\"itemName\":\"양파\",\"standard\":\"1KG/망\",\"unit\":\"망\",\"ordQty\":\"3\",\"billQty\":\"3\",\"unitPrice\":\"1500\",\"supplyPrice\":\"4500\",\"vat\":\"0\",\"totAmt\":\"4500\",\"taxCode\":\"No tax\",\"impHisNo\":\"\"},"
				+ "{\"orderType\":\"YAPI\",\"clientOrd\":\"OURORDER1\",\"clientOrdItem\":\"1\",\"billDate\":\"" + d + "\",\"itemCode\":\"222\",\"billQty\":\"1\"},"
				+ "{\"orderType\":\"YFOS\",\"clientOrd\":\"W1\",\"clientOrdItem\":\"000002\",\"billDate\":\"" + d + "\",\"itemCode\":\"333\",\"billQty\":\"0\"}"
				+ "]"));

		var result = service.runNow(command());

		assertThat(result).containsEntry("status", "DONE").containsEntry("saved_line_count", 1)
				.containsEntry("skipped_order_line_count", 1).containsEntry("zero_qty_line_count", 1);
		ArgumentCaptor<Map<String, Object>> header = ArgumentCaptor.forClass(Map.class);
		verify(mapper).upsertHistoryReceipt(header.capture());
		assertThat(header.getValue()).containsEntry("status", "HISTORY").containsEntry("receipt_date", day.toString())
				.containsEntry("provider_receipt_key", "EXT|A1|1000123|" + d + "|W1");
		ArgumentCaptor<Map<String, Object>> item = ArgumentCaptor.forClass(Map.class);
		verify(mapper).upsertHistoryReceiptItem(item.capture());
		var line = item.getValue();
		assertThat(line.get("provider_line_key")).isEqualTo("W|000001|111|1");
		assertThat((BigDecimal) line.get("base_received_qty")).isEqualByComparingTo("3000");
		assertThat(line).containsEntry("base_unit", "g").containsEntry("supplier_product_id", 100L);
		assertThat((BigDecimal) line.get("supply_amount")).isEqualByComparingTo("4500");
		assertThat(line.get("traceability_no")).isNull();
		verify(mapper).refreshHistoryReceiptTotals(Map.of("goods_receipt_id", header.getValue().get("goods_receipt_id")));
		// 재고에는 아무것도 넣지 않는다(입고 반영 서비스 자체를 쓰지 않음) — 기존 연결을 새 줄에 붙이는 것만
		verify(mapper).knownLinks(Map.of("account_id", "A1"));
	}

	@Test
	void receiptsOnOrAfterInventoryStartDateAreUnmatchedNotHistory() throws Exception {
		when(mapper.importSites(anyMap())).thenReturn(List.of(site("WELSTORY", 1L)));
		when(mapper.inventoryStartDate(anyMap())).thenReturn(day.toString());
		String d = day.toString().replace("-", "");
		when(welstory.call(anyString(), any())).thenReturn(welstoryResponse("[{\"clientOrd\":\"W2\",\"billDate\":\"" + d
				+ "\",\"itemCode\":\"111\",\"billQty\":\"2\",\"supplyPrice\":\"3000\"}]"));
		service.runNow(command());
		verify(mapper).upsertHistoryReceipt(argThat(p -> "UNMATCHED".equals(p.get("status"))));
	}

	/** ASP: 우리 발주 주문 줄은 입고가 아니라 공급사 청구 줄로 저장하고, 찾은 발주 품목을 대조한다 */
	@Test
	void ourOrderLinesBecomeBillLinesAndAreReconciled() throws Exception {
		var ledger = mock(PurchaseLedgerService.class);
		when(ledger.reconcile(anyCollection())).thenReturn(Map.of("checked", 1, "matched", 0, "price_diff", 1, "qty_diff", 0));
		service.setLedger(ledger);
		when(mapper.importSites(anyMap())).thenReturn(List.of(site("WELSTORY", 1L)));
		when(mapper.purchaseOrderCount(anyMap())).thenReturn(1);
		when(mapper.orderItemForBill(argThat(p -> "000001".equals(p.get("client_ord_item")))))
				.thenReturn(new HashMap<>(Map.of("purchase_order_id", "PO1", "purchase_order_item_id", 55L)));
		String d = day.toString().replace("-", "");
		when(welstory.call(anyString(), any())).thenReturn(welstoryResponse("["
				+ "{\"clientOrd\":\"OURORDER1\",\"clientOrdItem\":\"000001\",\"billDate\":\"" + d + "\",\"itemCode\":\"222\",\"billQty\":\"2\",\"unitPrice\":\"1500\",\"supplyPrice\":\"3000\",\"vat\":\"300\",\"totAmt\":\"3300\",\"taxCode\":\"Full tax\"},"
				+ "{\"clientOrd\":\"OURORDER1\",\"clientOrdItem\":\"000009\",\"billDate\":\"" + d + "\",\"itemCode\":\"333\",\"billQty\":\"1\",\"supplyPrice\":\"1000\"}"
				+ "]"));

		var result = service.runNow(command());

		assertThat(result).containsEntry("saved_line_count", 0).containsEntry("bill_line_count", 2)
				.containsEntry("unlinked_bill_line_count", 1).containsEntry("checked_order_item_count", 1)
				.containsEntry("amount_diff_item_count", 1);
		verify(mapper, never()).upsertHistoryReceiptItem(anyMap());
		verify(mapper).upsertBillLine(argThat(p -> Long.valueOf(55L).equals(p.get("purchase_order_item_id"))
				&& "TAXABLE".equals(p.get("tax_type")) && new BigDecimal("300").compareTo((BigDecimal) p.get("vat_amount")) == 0
				&& new BigDecimal("2").compareTo((BigDecimal) p.get("billed_qty")) == 0));
		verify(mapper).upsertBillLine(argThat(p -> p.get("purchase_order_item_id") == null && "000009".equals(p.get("client_ord_item"))));
		verify(ledger).reconcile(argThat(ids -> ids.size() == 1 && ids.contains(55L)));
	}

	@Test
	void unknownProductIsCreatedInactiveFromReceiptSpec() throws Exception {
		when(mapper.importSites(anyMap())).thenReturn(List.of(site("WELSTORY", 1L)));
		doReturn(null, new HashMap<>(Map.of("supplier_product_id", 200L, "base_qty", new BigDecimal("10000"), "base_unit", "g")))
				.when(mapper).productForHistory(anyMap());
		String d = day.toString().replace("-", "");
		when(welstory.call(anyString(), any())).thenReturn(welstoryResponse("[{\"clientOrd\":\"W3\",\"billDate\":\"" + d
				+ "\",\"itemCode\":\"999\",\"itemName\":\"단종 쌀\",\"standard\":\"10KG/BOX\",\"unit\":\"BOX\",\"billQty\":\"1\",\"supplyPrice\":\"30000\"}]"));
		var result = service.runNow(command());
		verify(mapper).insertHistoryProduct(argThat(p -> "999".equals(p.get("supplier_item_code")) && "단종 쌀".equals(p.get("product_name"))
				&& new BigDecimal("10000").compareTo((BigDecimal) p.get("base_qty")) == 0 && "g".equals(p.get("base_unit"))));
		assertThat(result).containsEntry("created_product_count", 1);
		verify(mapper).upsertHistoryReceiptItem(argThat(p -> Long.valueOf(200L).equals(p.get("supplier_product_id"))));
	}

	@Test
	void noDataDayIsNotAFailureAndProviderErrorIsRecordedPerDay() throws Exception {
		when(mapper.importSites(anyMap())).thenReturn(List.of(site("WELSTORY", 1L)));
		var command = command();
		command.put("from_date", day.minusDays(1).toString());
		when(welstory.call(anyString(), any())).thenReturn(
				json.readTree("{\"dataBody\":{\"resCd\":\"EE120\",\"resMsg\":\"조회 데이터가 존재하지 않습니다\"}}"),
				json.readTree("{\"dataBody\":{\"resCd\":\"E5002\",\"resMsg\":\"유효성 오류\"}}"));
		var result = service.runNow(command);
		assertThat(result).containsEntry("status", "DONE_WITH_ERRORS").containsEntry("failure_count", 1)
				.containsEntry("done_steps", 2).containsEntry("total_steps", 2);
		assertThat((List<?>) result.get("failures")).singleElement().asString().contains(day.toString()).contains("E5002");
	}

	@Test
	void ourhomeKeepsOnlyShippedUndeletedLinesAndSplitsTaxableAmounts() throws Exception {
		when(mapper.importSites(anyMap())).thenReturn(List.of(site("OURHOME", 2L)));
		var ohSite = new OurhomeSiteService.Site("FU0UP", "현장", "GA709", "본부", "", "", "", "", List.of());
		when(ourhome.requireSite("FU0UP")).thenReturn(ohSite);
		String d = day.toString().replace("-", "");
		when(ourhome.orderRows(ohSite, day)).thenReturn(List.of(
				json.readTree("{\"depositDt\":\"" + d + "\",\"ordKeyVal\":\"K1\",\"ordSeq\":\"1\",\"foSeq\":\"1\",\"goodcd\":\"G1\",\"goodnm\":\"돼지 앞다리\",\"goodsz\":\"1KG\",\"odrUnit\":\"KG\",\"clhaqty\":\"5\",\"salsUcost\":\"11000\",\"salsAmt\":\"60500\",\"txnSplamt\":\"55000\",\"dfSplamt\":\"0\",\"txtnTxamt\":\"5500\",\"prcsStatYn\":\"Y\",\"delYn\":\"N\",\"idenNo\":\"L123,L456\",\"expireInfo\":\"20261231\"}"),
				json.readTree("{\"depositDt\":\"" + d + "\",\"ordKeyVal\":\"K2\",\"goodcd\":\"G2\",\"clhaqty\":\"1\",\"prcsStatYn\":\"N\",\"delYn\":\"N\"}"),
				json.readTree("{\"depositDt\":\"" + d + "\",\"ordKeyVal\":\"K3\",\"goodcd\":\"G3\",\"clhaqty\":\"1\",\"prcsStatYn\":\"Y\",\"delYn\":\"Y\"}")));
		var result = service.runNow(command());
		assertThat(result).containsEntry("saved_line_count", 1);
		verify(mapper).upsertHistoryReceiptItem(argThat(p -> "O|1|1|G1|1".equals(p.get("provider_line_key"))
				&& new BigDecimal("55000").compareTo((BigDecimal) p.get("supply_amount")) == 0
				&& new BigDecimal("5500").compareTo((BigDecimal) p.get("vat_amount")) == 0
				&& new BigDecimal("60500").compareTo((BigDecimal) p.get("total_amount")) == 0
				&& "L123,L456".equals(p.get("traceability_no")) && "2026-12-31".equals(p.get("expiration_date"))));
		// 사업장은 작업마다 한 번만 확인(날짜마다 사업장 목록을 다시 받지 않음)
		verify(ourhome, times(1)).requireSite("FU0UP");
	}

	@Test
	void sameItemTwiceInOneOrderGetsDistinctLineKeys() throws Exception {
		when(mapper.importSites(anyMap())).thenReturn(List.of(site("WELSTORY", 1L)));
		String d = day.toString().replace("-", "");
		String row = "{\"orderType\":\"YPCU\",\"clientOrd\":\"X\",\"billDate\":\"" + d + "\",\"itemCode\":\"111\",\"billQty\":\"1\"}";
		when(welstory.call(anyString(), any())).thenReturn(welstoryResponse("[" + row + "," + row + "]"));
		service.runNow(command());
		verify(mapper).upsertHistoryReceiptItem(argThat(p -> "W||111|1".equals(p.get("provider_line_key"))));
		verify(mapper).upsertHistoryReceiptItem(argThat(p -> "W||111|2".equals(p.get("provider_line_key"))));
	}

	@Test
	void knownLinksAreAppliedOnlyToUnlinkedLines() {
		when(mapper.knownLinks(anyMap())).thenReturn(List.of(new HashMap<>(Map.of("supplier_product_id", 100L,
				"ingredient_id", "ONION", "base_unit", "g", "per_unit", new BigDecimal("1000")))));
		when(mapper.linkProductLines(anyMap())).thenReturn(4);
		assertThat(service.applyKnownLinks("A1")).isEqualTo(4);
		verify(mapper).linkProductLines(argThat(p -> Boolean.TRUE.equals(p.get("only_unlinked")) && "A1".equals(p.get("account_id"))
				&& "ONION".equals(p.get("ingredient_id"))));
	}

	@Test
	void rejectsFutureRangeTooLongRangeAndAccountWithoutSites() {
		when(mapper.importSites(anyMap())).thenReturn(List.of());
		var future = command();
		future.put("to_date", LocalDate.now().plusDays(2).toString());
		assertThatThrownBy(() -> service.runNow(future)).hasMessageContaining("오늘 이전");
		var tooLong = command();
		tooLong.put("from_date", day.minusDays(PurchaseHistoryImportService.MAX_DAYS).toString());
		assertThatThrownBy(() -> service.runNow(tooLong)).hasMessageContaining("까지 가져올 수 있습니다");
		assertThatThrownBy(() -> service.runNow(command())).hasMessageContaining("사업장이 없습니다");
	}

	@Test
	void expirationReadsFirstDate() {
		assertThat(PurchaseHistoryImportService.expiration("2026-12-31 외 1건")).isEqualTo(LocalDate.of(2026, 12, 31));
		assertThat(PurchaseHistoryImportService.expiration("")).isNull();
	}
}
