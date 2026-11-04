package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.DistAdminMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

class DistAdminServiceTest {
	private final DistAdminMapper mapper = mock(DistAdminMapper.class);
	private final DistAdminService service = new DistAdminService(mapper, new ObjectMapper());

	private void product(long id, String status) {
		Map<String, Object> row = new HashMap<>(Map.of("product_id", id, "partner_id", 7L, "sale_status", status));
		when(mapper.product(argThat(p -> p != null && Objects.equals(p.get("product_id"), id)))).thenReturn(row);
		when(mapper.dbNow()).thenReturn("2026-11-02 10:00:00");
	}

	@Test void onSaleNeedsCurrentPrice() {
		product(1, "DRAFT");
		when(mapper.currentSalePriceCount(any())).thenReturn(0);
		assertThatThrownBy(() -> service.changeStatus(new HashMap<>(Map.of("product_id", 1, "sale_status", "ON_SALE"))))
				.hasMessageContaining("판매가");
		verify(mapper, never()).updateSaleStatus(any());
	}

	@Test void stoppingSaleIsRecordedAsStop() {
		product(1, "ON_SALE");
		service.changeStatus(new HashMap<>(Map.of("product_id", 1, "sale_status", "PAUSED", "user_id", "hq1")));
		verify(mapper).updateSaleStatus(argThat(p -> "PAUSED".equals(p.get("sale_status"))));
		verify(mapper).insertHqHistory(argThat(p -> "STOP".equals(p.get("request_type")) && "hq1".equals(p.get("user_id"))));
	}

	@Test void newSalePriceClosesOldOneAtItsStart() {
		product(1, "ON_SALE");
		service.addSalePrice(new HashMap<>(Map.of("product_id", 1, "price_type", "account", "unit_price", "12,000")), "hq1");
		verify(mapper).closeSalePrices(argThat(p -> p.get("start_at").toString().equals("2026-11-02T10:00")));
		verify(mapper).insertSalePrice(argThat(p -> "ACCOUNT".equals(p.get("price_type")) && new BigDecimal("12000").equals(p.get("unit_price"))));
	}

	@Test void pastStartIsMovedToNowAndLaterPriceIsRejected() {
		product(1, "ON_SALE");
		when(mapper.laterSalePrices(any())).thenReturn(1);
		assertThatThrownBy(() -> service.addSalePrice(new HashMap<>(Map.of("product_id", 1, "price_type", "PERSONAL", "unit_price", 5000,
				"start_at", "2020-01-01 00:00")), "hq1")).hasMessageContaining("이후에 시작하는");
		verify(mapper).laterSalePrices(argThat(p -> p.get("start_at").toString().equals("2026-11-02T10:00")));
		verify(mapper, never()).insertSalePrice(any());
	}

	@Test void zeroPriceOnlyForInternal() {
		product(1, "ON_SALE");
		assertThatThrownBy(() -> service.addSalePrice(new HashMap<>(Map.of("product_id", 1, "price_type", "PERSONAL", "unit_price", 0)), "hq1"))
				.hasMessageContaining("0보다");
		service.addSalePrice(new HashMap<>(Map.of("product_id", 1, "price_type", "INTERNAL", "unit_price", 0)), "hq1");
		verify(mapper).insertSalePrice(any());
	}

	@Test void accountOnlyPriceNeedsKnownAccount() {
		product(1, "ON_SALE");
		when(mapper.accountExists(any())).thenReturn(0);
		assertThatThrownBy(() -> service.addSalePrice(new HashMap<>(Map.of("product_id", 1, "price_type", "ACCOUNT", "unit_price", 100, "account_id", "X")), "hq1"))
				.hasMessageContaining("거래처를 찾을 수 없습니다");
		assertThatThrownBy(() -> service.addSalePrice(new HashMap<>(Map.of("product_id", 1, "price_type", "PERSONAL", "unit_price", 100, "account_id", "X")), "hq1"))
				.hasMessageContaining("거래처가·행사가");
	}

	@Test void productNeedsSellerFirst() {
		when(mapper.partnerExists(any())).thenReturn(1);
		when(mapper.dbNow()).thenReturn("2026-11-02 10:00:00");
		when(mapper.seller()).thenReturn(null);
		assertThatThrownBy(() -> service.saveProduct(new HashMap<>(Map.of("partner_id", 7, "product_name", "완도 활전복", "fulfillment_type", "DROP_SHIP"))))
				.hasMessageContaining("판매 주체");
		verify(mapper, never()).insertProduct(any());
	}

	@Test void availabilityCannotDropBelowReserved() {
		product(1, "ON_SALE");
		when(mapper.reservedQty(any())).thenReturn(new BigDecimal("30.000"));
		assertThatThrownBy(() -> service.saveAvailability(new HashMap<>(Map.of("product_id", 1, "availability_id", 5, "available_from", "2026-11-02", "available_qty", 20))))
				.hasMessageContaining("30");
		verify(mapper, never()).updateAvailability(any());
	}

	@Test void sameComparesNumbersAndTextLoosely() {
		assertThat(DistAdminService.same(new BigDecimal("1.000"), new BigDecimal("1"))).isTrue();
		assertThat(DistAdminService.same(null, "")).isTrue();
		assertThat(DistAdminService.same(7L, new BigDecimal("7"))).isTrue();
		assertThat(DistAdminService.same("a", "b")).isFalse();
	}

	@Test void productInfoKeepsFilledItemsOnly() {
		String saved = service.productInfo("{\"type\":\"PROCESSED_FOOD\",\"type_name\":\"가공식품\",\"items\":["
				+ "{\"key\":\"product_name\",\"label\":\"제품명\",\"value\":\" 전복장 \"},{\"key\":\"gmo\",\"label\":\"유전자변형\",\"value\":\"\"},"
				+ "{\"label\":\"직접 추가\",\"value\":\"내용\"}]}", "상품정보제공고시", true);
		assertThat(saved).isEqualTo("{\"type\":\"PROCESSED_FOOD\",\"type_name\":\"가공식품\",\"items\":["
				+ "{\"key\":\"product_name\",\"label\":\"제품명\",\"value\":\"전복장\"},{\"label\":\"직접 추가\",\"value\":\"내용\"}]}");
		assertThat(service.productInfo("{\"items\":[{\"label\":\"제품명\",\"value\":\" \"}]}", "식품 표시사항", false)).isNull();
		assertThat(service.productInfo("", "식품 표시사항", false)).isNull();
	}

	@Test void productInfoRejectsWrongShape() {
		assertThatThrownBy(() -> service.productInfo("{\"a\":1}", "식품 표시사항", false)).hasMessageContaining("형식");
		assertThatThrownBy(() -> service.productInfo("not json", "식품 표시사항", false)).hasMessageContaining("형식");
		assertThatThrownBy(() -> service.productInfo("{\"items\":[{\"label\":\"a\",\"value\":\"b\"}]}", "상품정보제공고시", true)).hasMessageContaining("종류");
		assertThatThrownBy(() -> service.productInfo("{\"items\":[{\"label\":\"\",\"value\":\"b\"}]}", "식품 표시사항", false)).hasMessageContaining("이름");
	}

	@Test void savingContentCleansHtmlAndRecordsShortHistory() {
		product(1, "DRAFT");
		Map<String, Object> body = new HashMap<>(Map.of("product_id", 1, "partner_id", 7, "product_name", "전복장", "fulfillment_type", "DROP_SHIP",
				"sale_target", "ALL", "tax_type", "TAXABLE", "storage_type", "CHILLED", "description_html", "<p onclick=\"x()\">맛있는 전복장</p><script>1</script>",
				"food_label_json", "{\"items\":[{\"key\":\"product_name\",\"label\":\"제품명\",\"value\":\"전복장\"}]}"));
		body.put("user_id", "hq1");
		body.put("product_code", "P1");
		when(mapper.partnerExists(any())).thenReturn(1);
		service.saveProduct(body);
		verify(mapper).saveContent(argThat(p -> "<p>맛있는 전복장</p>".equals(p.get("description_html")) && p.get("food_label_json").toString().contains("전복장")
				&& p.get("notice_json") == null));
		verify(mapper).insertHqHistory(argThat(p -> p.get("after_json").toString().contains("(HTML 14자)") && p.get("after_json").toString().contains("(1개 항목)")
				&& !p.get("after_json").toString().contains("<p>")));
	}
}
