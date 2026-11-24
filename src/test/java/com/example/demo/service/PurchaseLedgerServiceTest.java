package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.example.demo.mapper.PurchaseLedgerMapper;

/** 매입 관리(ASP): 금액 추정·과세 구분 맞추기·공급사 청구 대조 */
class PurchaseLedgerServiceTest {
	final PurchaseLedgerMapper mapper = mock(PurchaseLedgerMapper.class);
	final PurchaseLedgerService service = new PurchaseLedgerService(mapper, PurchasePriceBasis.excludingVat());

	@Test
	void taxTypesFromEachSupplierBecomeTwoCodes() {
		assertThat(PurchaseAmounts.taxType("Full tax")).isEqualTo("TAXABLE");
		assertThat(PurchaseAmounts.taxType("CONS(소모품)")).isEqualTo("TAXABLE");
		assertThat(PurchaseAmounts.taxType("Y")).isEqualTo("TAXABLE");
		assertThat(PurchaseAmounts.taxType("No tax")).isEqualTo("TAX_FREE");
		assertThat(PurchaseAmounts.taxType("N")).isEqualTo("TAX_FREE");
		assertThat(PurchaseAmounts.taxType("UNKNOWN")).isNull();
		assertThat(PurchaseAmounts.taxType(null)).isNull();
	}

	@Test
	void estimateAddsTenPercentVatOnlyForTaxableAndCutsBelowOneWon() {
		var taxable = PurchaseAmounts.estimate(new BigDecimal("3"), new BigDecimal("1235"), "TAXABLE", false);
		assertThat(taxable.supply()).isEqualByComparingTo("3705");
		assertThat(taxable.vat()).isEqualByComparingTo("370"); // 370.5 → 370
		assertThat(taxable.total()).isEqualByComparingTo("4075");
		var free = PurchaseAmounts.estimate(new BigDecimal("2"), new BigDecimal("1500"), "TAX_FREE", false);
		assertThat(free.vat()).isEqualByComparingTo("0");
		assertThat(free.total()).isEqualByComparingTo("3000");
		var unknown = PurchaseAmounts.estimate(new BigDecimal("2"), new BigDecimal("1500"), null, false);
		assertThat(unknown.vat()).isEqualByComparingTo("0");
		// 단가가 부가세 포함인 공급사: 11000 → 공급가 10000 + 부가세 1000
		var included = PurchaseAmounts.estimate(new BigDecimal("1"), new BigDecimal("11000"), "TAXABLE", true);
		assertThat(included.supply()).isEqualByComparingTo("10000");
		assertThat(included.vat()).isEqualByComparingTo("1000");
		assertThat(included.total()).isEqualByComparingTo("11000");
	}

	@Test
	void priceBasisSettingReadsSupplierCodes() {
		var basis = new PurchasePriceBasis(" ourhome , ");
		assertThat(basis.includesVat("OURHOME")).isTrue();
		assertThat(basis.includesVat("WELSTORY")).isFalse();
		assertThat(PurchasePriceBasis.excludingVat().includesVat("OURHOME")).isFalse();
	}

	Map<String, Object> receipt(long id, String receiptId, String qty, String unitPrice) {
		return new HashMap<>(Map.of("goods_receipt_item_id", id, "goods_receipt_id", receiptId, "purchase_order_item_id", 9L,
				"received_qty", new BigDecimal(qty), "unit_price", new BigDecimal(unitPrice), "supply_amount",
				new BigDecimal(qty).multiply(new BigDecimal(unitPrice)), "supplier_code", "WELSTORY", "amount_source", "ORDER_ESTIMATE"));
	}

	Map<String, Object> bill(String qty, String supply, String vat, String taxType) {
		var m = new HashMap<String, Object>(Map.of("purchase_order_item_id", 9L, "billed_qty", new BigDecimal(qty), "supply_amount",
				new BigDecimal(supply), "vat_amount", new BigDecimal(vat), "total_amount", new BigDecimal(supply).add(new BigDecimal(vat))));
		m.put("tax_type", taxType);
		return m;
	}

	@SuppressWarnings("unchecked")
	List<Map<String, Object>> applied() {
		ArgumentCaptor<Map<String, Object>> c = ArgumentCaptor.forClass(Map.class);
		verify(mapper, atLeastOnce()).applyCheck(c.capture());
		return c.getAllValues();
	}

	@Test
	void sameQuantityTakesSupplierAmountsSplitByQuantity() {
		// 두 번에 나눠 입고(1 + 2) — 공급사는 한 줄로 3개 청구
		when(mapper.billTotals(anyMap())).thenReturn(List.of(bill("3", "4500", "450", "TAXABLE")));
		when(mapper.orderReceiptItems(anyMap())).thenReturn(List.of(receipt(1, "R1", "1", "1500"), receipt(2, "R2", "2", "1500")));

		var counts = service.reconcile(List.of(9L));

		assertThat(counts).containsEntry("checked", 1).containsEntry("matched", 1);
		var rows = applied();
		assertThat(rows).hasSize(2);
		assertThat((BigDecimal) rows.get(0).get("supply_amount")).isEqualByComparingTo("1500");
		assertThat((BigDecimal) rows.get(0).get("vat_amount")).isEqualByComparingTo("150");
		assertThat((BigDecimal) rows.get(1).get("supply_amount")).isEqualByComparingTo("3000");
		assertThat((BigDecimal) rows.get(1).get("vat_amount")).isEqualByComparingTo("300");
		assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("amount_check_status", "MATCHED").containsEntry("tax_type", "TAXABLE"));
		verify(mapper).refreshReceiptTotals(Map.of("goods_receipt_id", "R1"));
		verify(mapper).refreshReceiptTotals(Map.of("goods_receipt_id", "R2"));
	}

	@Test
	void differentPriceIsMarkedButSupplierAmountIsUsed() {
		when(mapper.billTotals(anyMap())).thenReturn(List.of(bill("2", "3200", "0", "No tax")));
		when(mapper.orderReceiptItems(anyMap())).thenReturn(List.of(receipt(1, "R1", "2", "1500")));

		assertThat(service.reconcile(List.of(9L))).containsEntry("price_diff", 1);
		var row = applied().get(0);
		assertThat(row).containsEntry("amount_check_status", "PRICE_DIFF").containsEntry("tax_type", "TAX_FREE");
		assertThat((BigDecimal) row.get("supply_amount")).isEqualByComparingTo("3200");
	}

	@Test
	void differentQuantityKeepsOurAmountsForPeopleToCheck() {
		when(mapper.billTotals(anyMap())).thenReturn(List.of(bill("1", "1500", "150", "Full tax")));
		when(mapper.orderReceiptItems(anyMap())).thenReturn(List.of(receipt(1, "R1", "2", "1500")));

		assertThat(service.reconcile(List.of(9L))).containsEntry("qty_diff", 1);
		var row = applied().get(0);
		assertThat(row).containsEntry("amount_check_status", "QTY_DIFF").doesNotContainKey("supply_amount");
		verify(mapper, never()).refreshReceiptTotals(anyMap());
	}

	@Test
	void receiptWithoutBillIsLeftUnchecked() {
		when(mapper.billTotals(anyMap())).thenReturn(List.of());
		when(mapper.orderReceiptItems(anyMap())).thenReturn(List.of(receipt(1, "R1", "2", "1500")));
		assertThat(service.reconcile(List.of(9L))).containsEntry("checked", 0);
		verify(mapper, never()).applyCheck(anyMap());
	}

	@Test
	void verdictNeedsNinetyPercentOnOneSide() {
		assertThat(PurchaseLedgerService.verdict(null)).isEqualTo("NO_DATA");
		assertThat(PurchaseLedgerService.verdict(Map.of("line_count", 10, "excludes_vat_count", 9, "includes_vat_count", 0))).isEqualTo("EXCLUDES_VAT");
		assertThat(PurchaseLedgerService.verdict(Map.of("line_count", 10, "excludes_vat_count", 0, "includes_vat_count", 10))).isEqualTo("INCLUDES_VAT");
		assertThat(PurchaseLedgerService.verdict(Map.of("line_count", 10, "excludes_vat_count", 5, "includes_vat_count", 5))).isEqualTo("MIXED");
	}

	@Test
	void filtersNeedPeriodWithinOneYear() {
		assertThatThrownBy(() -> PurchaseLedgerService.filters(Map.of("from_date", "2026-01-01")))
				.hasMessageContaining("to_date");
		assertThatThrownBy(() -> PurchaseLedgerService.filters(Map.of("from_date", "2025-01-01", "to_date", "2026-11-01")))
				.hasMessageContaining("366");
		assertThatThrownBy(() -> PurchaseLedgerService.filters(Map.of("from_date", "2026-11-01", "to_date", "2026-11-02", "supplier_id", "1 OR 1")))
				.hasMessageContaining("supplier_id");
		var q = PurchaseLedgerService.filters(Map.of("from_date", "2026-11-01", "to_date", "2026-11-30", "source", "order"));
		assertThat(q).containsEntry("source", "ORDER");
	}
}
