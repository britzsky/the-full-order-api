package com.example.demo.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 공급사 발주 단가가 부가세 포함인지(ASP, 2026-11-23). 기본은 모두 '부가세 별도'(웰스토리 입고내역이 unitPrice·supplyPrice·vat를 따로 줌).
 * 구매 이력 단가 확인(/v2/purchase-ledger/price-check)에서 '포함'으로 나오면 purchase-ledger.price-includes-vat-suppliers에
 * 공급사 코드(WELSTORY, OURHOME …)를 쉼표로 적는다.
 */
@Component
public class PurchasePriceBasis {
	private final Set<String> includesVat;

	public PurchasePriceBasis(@Value("${purchase-ledger.price-includes-vat-suppliers:}") String suppliers) {
		this.includesVat = Arrays.stream(suppliers == null ? new String[0] : suppliers.split(","))
				.map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
	}

	public static PurchasePriceBasis excludingVat() {
		return new PurchasePriceBasis("");
	}

	public boolean includesVat(Object supplierCode) {
		return supplierCode != null && includesVat.contains(supplierCode.toString().trim().toUpperCase(Locale.ROOT));
	}

	public Set<String> suppliersIncludingVat() {
		return includesVat;
	}
}
