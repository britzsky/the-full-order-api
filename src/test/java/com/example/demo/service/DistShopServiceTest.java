package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.DistAdminMapper;
import com.example.demo.mapper.DistShopMapper;
import com.example.demo.security.WorkspaceUser;

class DistShopServiceTest {
	private final DistShopMapper shop = mock(DistShopMapper.class);
	private final DistShopService service = new DistShopService(shop, mock(DistAdminMapper.class), new DistCrypto(""));
	private final WorkspaceUser hq = new WorkspaceUser("hq1", "2", "");
	private final WorkspaceUser site = new WorkspaceUser("site1", "3", "A1");

	private static Map<String, Object> product(long id, long partner, String price, String fee, String bundle, String freeAt) {
		Map<String, Object> row = new HashMap<>();
		row.put("product_id", id);
		row.put("partner_id", partner);
		row.put("product_name", "상품" + id);
		row.put("sale_status", "ON_SALE");
		row.put("unit_price", new BigDecimal(price));
		row.put("shipping_fee", new BigDecimal(fee));
		row.put("bundle_ship_yn", bundle);
		row.put("free_shipping_amount", freeAt == null ? null : new BigDecimal(freeAt));
		row.put("min_order_qty", BigDecimal.ONE);
		row.put("tax_type", "TAXABLE");
		return row;
	}

	private static List<Map<String, Object>> items(Object... pairs) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (int i = 0; i < pairs.length; i += 2) list.add(Map.of("product_id", pairs[i], "qty", pairs[i + 1]));
		return list;
	}

	@Test void hqCannotBuyAsAccount() {
		assertThatThrownBy(() -> service.scope(hq, "ACCOUNT")).hasMessageContaining("개인 구매만");
		assertThat(service.scope(hq, "").get("buyer_type")).isEqualTo("PERSONAL");
		assertThat(service.scope(site, "").get("account_id")).isEqualTo("A1");
		assertThat(service.scope(site, "PERSONAL").get("account_id")).isNull();
	}

	@Test void shippingBundlesPerPartnerAndFreesOverThreshold() {
		when(shop.products(any())).thenReturn(List.of(
				product(1, 10, "10000", "3000", "Y", "50000"), product(2, 10, "20000", "4000", "Y", null),
				product(3, 20, "5000", "2500", "N", null)));
		var quote = service.price(service.scope(site, "ACCOUNT"), items(1, 1, 2, 1, 3, 2), false);
		// 협력사 10: 합포장 → 비싼 4000 한 번, 협력사 20: 따로 포장 2500 × 2
		assertThat(quote.shippingFee).isEqualByComparingTo("9000");
		assertThat(quote.itemAmount).isEqualByComparingTo("40000");
		assertThat(quote.total).isEqualByComparingTo("49000");
		assertThat(quote.groups.get(0).get("free_shipping_left")).isEqualTo(new BigDecimal("20000"));

		var free = service.price(service.scope(site, "ACCOUNT"), items(1, 3, 2, 1), false);
		assertThat(free.shippingFee).isEqualByComparingTo("0");
	}

	@Test void taxSplitsSupplyAndVatAndKeepsTaxFreeApart() {
		Map<String, Object> taxFree = product(1, 10, "33000", "0", "Y", null);
		taxFree.put("tax_type", "TAX_FREE");
		when(shop.products(any())).thenReturn(List.of(taxFree, product(2, 10, "11000", "0", "Y", null)));
		var quote = service.price(service.scope(site, "ACCOUNT"), items(1, 1, 2, 1), false);
		assertThat(quote.taxFree).isEqualByComparingTo("33000");
		assertThat(quote.taxable).isEqualByComparingTo("10000");
		assertThat(quote.vat).isEqualByComparingTo("1000");
	}

	@Test void quantityRules() {
		Map<String, Object> row = product(1, 10, "1000", "0", "Y", null);
		row.put("min_order_qty", new BigDecimal("2"));
		row.put("max_order_qty", new BigDecimal("5"));
		row.put("available_qty", new BigDecimal("10"));
		row.put("reserved_qty", new BigDecimal("7"));
		when(shop.products(any())).thenReturn(List.of(row));
		var scope = service.scope(site, "ACCOUNT");
		assertThatThrownBy(() -> service.price(scope, items(1, 1), false)).hasMessageContaining("최소 2개");
		assertThatThrownBy(() -> service.price(scope, items(1, 6), false)).hasMessageContaining("5개까지");
		assertThatThrownBy(() -> service.price(scope, items(1, 4), false)).hasMessageContaining("남은 수량이 3개");
		assertThatThrownBy(() -> service.price(scope, items(1, "1.5"), false)).hasMessageContaining("정수");
		assertThatThrownBy(() -> service.price(scope, items(9, 1), false)).hasMessageContaining("판매하지 않는");
		assertThat(service.price(scope, items(1, 1, 1, 2), false).lines.get(0).qty).isEqualByComparingTo("3");
	}

	@Test void soldOutAndUnavailableCannotBeOrdered() {
		Map<String, Object> soldOut = product(1, 10, "1000", "0", "Y", null);
		soldOut.put("sale_status", "SOLD_OUT");
		when(shop.products(any())).thenReturn(List.of(soldOut));
		assertThatThrownBy(() -> service.price(service.scope(site, "ACCOUNT"), items(1, 1), false)).hasMessageContaining("품절");
		Map<String, Object> off = product(2, 10, "1000", "0", "Y", null);
		off.put("availability_status", "UNAVAILABLE");
		when(shop.products(any())).thenReturn(List.of(off));
		assertThatThrownBy(() -> service.price(service.scope(site, "ACCOUNT"), items(2, 1), false)).hasMessageContaining("판매하지 않습니다");
	}

	@Test void cancelPolicyByState() {
		assertThat(DistShopService.cancelPolicy(Map.of("order_status", "PENDING_PAYMENT", "payment_status", "WAITING"), false, false).get("mode")).isEqualTo("IMMEDIATE");
		assertThat(DistShopService.cancelPolicy(Map.of("order_status", "PAID", "payment_status", "CREDIT"), false, false).get("mode")).isEqualTo("IMMEDIATE");
		assertThat(DistShopService.cancelPolicy(Map.of("order_status", "PAID", "payment_status", "PAID"), false, false).get("mode")).isEqualTo("REQUEST");
		assertThat(DistShopService.cancelPolicy(Map.of("order_status", "PAID", "payment_status", "CREDIT"), true, false).get("mode")).isEqualTo("NONE");
		assertThat(DistShopService.cancelPolicy(Map.of("order_status", "SHIPPED", "payment_status", "PAID"), false, false).get("mode")).isEqualTo("NONE");
		assertThat(DistShopService.cancelPolicy(Map.of("order_status", "PAID", "payment_status", "PAID"), false, true).get("mode")).isEqualTo("NONE");
	}

	@Test void creditBlockedByProfile() {
		when(shop.outstandingCredit(any())).thenReturn(new BigDecimal("80000"));
		assertThat(service.creditStatus("A1", null, BigDecimal.ZERO).get("allowed")).isEqualTo(true);
		assertThat(service.creditStatus("A1", Map.of("payment_terms", "PREPAID"), BigDecimal.ZERO).get("reason").toString()).contains("선결제");
		assertThat(service.creditStatus("A1", Map.of("payment_terms", "MONTHLY", "credit_hold_yn", "Y"), BigDecimal.ZERO).get("allowed")).isEqualTo(false);
		Map<String, Object> limited = Map.of("payment_terms", "MONTHLY", "credit_hold_yn", "N", "credit_limit", new BigDecimal("100000"));
		assertThat(service.creditStatus("A1", limited, new BigDecimal("20000")).get("allowed")).isEqualTo(true);
		assertThat(service.creditStatus("A1", limited, new BigDecimal("20001")).get("reason").toString()).contains("한도");
	}
}
