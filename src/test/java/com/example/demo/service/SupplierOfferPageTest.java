package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;

import org.junit.jupiter.api.Test;

import com.example.demo.mapper.SupplierIntegrationMapper;

/** 공급사 통합 연동 상품·판가 표: 전체(2만여 건)를 보내지 않고 한 페이지 + 전체·주문 불가 건수만. */
class SupplierOfferPageTest {
	/** 250건, 7의 배수 번호는 판매 중단(주문 불가) */
	SupplierIntegrationService service() {
		var mapper = mock(SupplierIntegrationMapper.class);
		when(mapper.offers(anyMap())).thenAnswer(inv -> {
			List<Map<String, Object>> rows = new ArrayList<>();
			for (int i = 1; i <= 250; i++) {
				Map<String, Object> row = new HashMap<>();
				row.put("supplier_offer_id", (long) i);
				row.put("product_name", "상품" + i);
				row.put("supplier_item_code", "C" + i);
				row.put("supplier_code", "WELSTORY");
				row.put("product_status", i % 7 == 0 ? "STOP" : "ACTIVE");
				row.put("purchase_price", new BigDecimal("1000"));
				row.put("base_qty", BigDecimal.ONE);
				row.put("base_unit", "EA");
				row.put("order_unit", "EA");
				rows.add(row);
			}
			return rows;
		});
		return new SupplierIntegrationService(mapper, mock(SupplierSiteService.class));
	}

	Map<String, Object> page(Map<String, Object> extra) {
		Map<String, Object> params = new HashMap<>(Map.of("account_id", "A", "delivery_date", "2099-12-01"));
		params.putAll(extra);
		return service().offersPage(params);
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> rows(Map<String, Object> page) {
		return (List<Map<String, Object>>) page.get("rows");
	}

	@Test
	void returnsOnePageWithTotalsForWholeList() {
		var first = page(Map.of());
		assertThat(rows(first)).hasSize(100);
		assertThat(first).containsEntry("total_count", 250).containsEntry("all_count", 250).containsEntry("unavailable_count", 35)
				.containsEntry("page", 1).containsEntry("size", 100);

		var last = page(Map.of("page", "3", "size", "100"));
		assertThat(rows(last)).hasSize(50);
		assertThat(rows(last).get(0)).containsEntry("supplier_offer_id", 201L);
		assertThat(rows(page(Map.of("page", "9")))).isEmpty();
	}

	@Test
	void unavailableOnlyPagesOverUnavailableRows() {
		var result = page(Map.of("unavailable_only", "Y", "size", "20"));
		assertThat(result).containsEntry("total_count", 35).containsEntry("all_count", 250).containsEntry("unavailable_count", 35);
		assertThat(rows(result)).hasSize(20).allSatisfy(row -> assertThat(row).containsEntry("orderable_yn", "N"));
	}

	@Test
	void rejectsBadPageAndOversizedPage() {
		assertThatThrownBy(() -> page(Map.of("page", "0"))).hasMessageContaining("page");
		assertThatThrownBy(() -> page(Map.of("size", "5000"))).hasMessageContaining("size");
		assertThatThrownBy(() -> page(Map.of("page", "x"))).hasMessageContaining("page");
	}
}
