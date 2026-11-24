package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 매입 금액 계산(ASP, 2026-11-23). 공급사마다 다른 과세 구분 값을 TAXABLE/TAX_FREE로 맞추고, 발주 단가로 공급가·부가세를 추정한다.
 * 추정값은 공급사 청구 내역(구매 이력 가져오기)이 오면 공급사 금액으로 바뀐다(PurchaseLedgerService.reconcile).
 */
public final class PurchaseAmounts {
	public static final String TAXABLE = "TAXABLE", TAX_FREE = "TAX_FREE";
	static final BigDecimal VAT_RATE = new BigDecimal("0.1");

	private PurchaseAmounts() {
	}

	/**
	 * 웰스토리 taxCode 'Full tax'(과세)·'No tax'(면세)·'CONS(소모품)'(과세), 아워홈 taxYn Y/N(카탈로그 동기화가 Full tax/No tax로 저장),
	 * 유통 TAXABLE/TAX_FREE. 모르는 값(UNKNOWN 등)은 null.
	 */
	public static String taxType(Object raw) {
		String v = raw == null ? "" : raw.toString().trim().toUpperCase(Locale.ROOT);
		if (v.isEmpty())
			return null;
		if (v.equals("NO TAX") || v.equals("N") || v.equals("TAX_FREE") || v.equals("EXEMPT") || v.equals("면세"))
			return TAX_FREE;
		if (v.equals("FULL TAX") || v.startsWith("CONS") || v.equals("Y") || v.equals("TAXABLE") || v.equals("과세"))
			return TAXABLE;
		return null;
	}

	public record Amounts(BigDecimal supply, BigDecimal vat, BigDecimal total) {
	}

	/**
	 * 발주 단가 × 수량으로 금액을 추정한다. 과세: 단가가 부가세 별도면 부가세 = 공급가의 10%(원 미만 버림), 부가세 포함이면
	 * 공급가 = 합계 ÷ 1.1(원 미만 반올림). 면세·미확인: 부가세 0(미확인은 공급사 청구로 채워질 때까지 0).
	 */
	public static Amounts estimate(BigDecimal qty, BigDecimal unitPrice, String taxType, boolean priceIncludesVat) {
		BigDecimal gross = qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
		if (!TAXABLE.equals(taxType))
			return new Amounts(gross, BigDecimal.ZERO.setScale(2), gross);
		if (priceIncludesVat) {
			BigDecimal supply = gross.divide(BigDecimal.ONE.add(VAT_RATE), 0, RoundingMode.HALF_UP).setScale(2);
			return new Amounts(supply, gross.subtract(supply), gross);
		}
		BigDecimal vat = gross.multiply(VAT_RATE).setScale(0, RoundingMode.DOWN).setScale(2);
		return new Amounts(gross, vat, gross.add(vat));
	}
}
