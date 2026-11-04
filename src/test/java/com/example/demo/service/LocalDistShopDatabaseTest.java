package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.security.WorkspaceUser;

/**
 * 더채움몰 주문 흐름(2026-11-02)을 로컬 DB의 실제 SQL로 확인한다. 테스트마다 롤백. dist.pii.key가 비밀 설정에 있어야 함.
 * 실행: RUN_LOCAL_DB_TESTS=true + 로컬 DB 접속 환경변수.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalDistShopDatabaseTest {
	@Autowired JdbcTemplate jdbc;
	@Autowired DistAdminService admin;
	@Autowired DistShopService shop;

	String account;
	WorkspaceUser site, other, hq;
	long product;

	void setUp(boolean withDeposit) {
		account = jdbc.queryForObject("SELECT account_id FROM the_full.tb_account ORDER BY account_id LIMIT 1", String.class);
		site = new WorkspaceUser("zz_site_buyer", "3", account);
		other = new WorkspaceUser("zz_site_other", "3", account);
		hq = new WorkspaceUser("zz_hq_buyer", "2", "");
		Map<String, Object> seller = new HashMap<>(Map.of("seller_name", "테스트 더채움", "biz_no", "1234567890", "user_id", "TEST"));
		if (withDeposit) seller.putAll(Map.of("deposit_bank_name", "국민은행", "deposit_account_no", "123-456-789", "deposit_account_holder", "더채움"));
		admin.saveSeller(seller);
		long partner = ((Number) admin.savePartner(new HashMap<>(Map.of("partner_code", "ZZ_SHOP_TEST", "partner_name", "테스트 완도", "user_id", "TEST"))).get("partner_id")).longValue();
		Map<String, Object> body = new HashMap<>(Map.of("partner_id", partner, "product_name", "테스트 활전복", "fulfillment_type", "DROP_SHIP",
				"tax_type", "TAX_FREE", "shipping_fee", "4000", "free_shipping_amount", "100000", "user_id", "TEST"));
		body.put("account_price", "45000");
		body.put("personal_price", "52000");
		body.put("unit_cost", "38000");
		product = ((Number) admin.saveProduct(body).get("product_id")).longValue();
		admin.changeStatus(new HashMap<>(Map.of("product_id", product, "sale_status", "ON_SALE", "user_id", "TEST")));
		String today = jdbc.queryForObject("SELECT DATE_FORMAT(CURDATE(),'%Y-%m-%d')", String.class);
		admin.saveAvailability(new HashMap<>(Map.of("product_id", product, "available_from", today, "available_qty", 5, "user_id", "TEST")));
	}

	Map<String, Object> orderBody(String key, String buyerType, String method, int qty) {
		Map<String, Object> body = new HashMap<>();
		body.put("client_request_key", key);
		body.put("buyer_type", buyerType);
		body.put("payment_method", method);
		body.put("items", List.of(Map.of("product_id", product, "qty", qty)));
		body.put("orderer", Map.of("name", "홍길동", "phone", "010-1234-5678"));
		body.put("recipient", Map.of("name", "김수령", "phone", "01098765432", "zipcode", "12345", "address1", "서울시 테스트구 1", "address2", "101호"));
		body.put("privacy_agreed", true);
		return body;
	}

	BigDecimal reserved() {
		return jdbc.queryForObject("SELECT reserved_qty FROM the_full_order.tb_dist_product_availability WHERE product_id=?", BigDecimal.class, product);
	}

	@SuppressWarnings("unchecked")
	@Test void accountBuyerOrdersSeesOnlyOwnOrdersAndCancels() {
		setUp(true);
		var rows = (List<Map<String, Object>>) shop.products(site, Map.of("keyword", "테스트 활전복")).get("products");
		assertThat(rows).hasSize(1);
		assertThat(new BigDecimal(rows.get(0).get("unit_price").toString())).isEqualByComparingTo("45000");
		var personal = (List<Map<String, Object>>) shop.products(site, Map.of("keyword", "테스트 활전복", "buyer_type", "PERSONAL")).get("products");
		assertThat(new BigDecimal(personal.get(0).get("unit_price").toString())).isEqualByComparingTo("52000");

		var quote = shop.quote(site, orderBody("q", "ACCOUNT", "CREDIT", 2));
		assertThat((BigDecimal) quote.get("total_amount")).isEqualByComparingTo("94000"); // 90000 + 배송비 4000
		assertThat((BigDecimal) quote.get("tax_free_amount")).isEqualByComparingTo("90000");

		var placed = shop.placeOrder(site, orderBody("zz-test-key-0001", "ACCOUNT", "CREDIT", 2));
		long orderId = ((Number) placed.get("order_id")).longValue();
		assertThat(placed.get("order_no").toString()).matches("\\d{8}-\\d{4}");
		assertThat(reserved()).isEqualByComparingTo("2");
		assertThat(shop.placeOrder(site, orderBody("zz-test-key-0001", "ACCOUNT", "CREDIT", 2)).get("duplicate")).isEqualTo(true);
		assertThat(reserved()).isEqualByComparingTo("2");

		// 개인정보는 암호문으로 저장, 본인 조회에서만 풀림
		String stored = jdbc.queryForObject("SELECT orderer_name_enc FROM the_full_order.tb_dist_order WHERE order_id=?", String.class, orderId);
		assertThat(stored).startsWith("v1:").doesNotContain("홍길동");
		var detail = shop.myOrder(site, orderId);
		assertThat(((Map<String, Object>) detail.get("order")).get("orderer_name")).isEqualTo("홍길동");
		assertThat(((Map<String, Object>) detail.get("recipient")).get("address2")).isEqualTo("101호");
		assertThat(((Map<String, Object>) detail.get("order")).get("payment_status")).isEqualTo("CREDIT");
		assertThat(((Map<String, Object>) detail.get("cancel")).get("mode")).isEqualTo("IMMEDIATE");

		// 같은 거래처의 다른 직원도 남의 주문은 못 본다
		assertThat((List<?>) shop.myOrders(other, "").get("orders")).isEmpty();
		assertThatThrownBy(() -> shop.myOrder(other, orderId)).hasMessageContaining("본인");
		assertThatThrownBy(() -> shop.cancel(other, orderId, Map.of())).hasMessageContaining("본인");
		assertThat((List<?>) shop.myOrders(site, "ACTIVE").get("orders")).hasSize(1);

		// 남은 수량 3개 → 4개 주문은 거절
		assertThatThrownBy(() -> shop.placeOrder(site, orderBody("zz-test-key-0002", "ACCOUNT", "CREDIT", 4))).hasMessageContaining("남은 수량이 3개");

		shop.cancel(site, orderId, Map.of("reason_code", "CHANGE_MIND", "reason", "수량 착오"));
		assertThat(reserved()).isEqualByComparingTo("0");
		assertThat(jdbc.queryForObject("SELECT order_status FROM the_full_order.tb_dist_order WHERE order_id=?", String.class, orderId)).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT status FROM the_full_order.tb_dist_claim WHERE order_id=?", String.class, orderId)).isEqualTo("COMPLETED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_dist_claim_item c JOIN the_full_order.tb_dist_claim m ON m.claim_id=c.claim_id WHERE m.order_id=?", Integer.class, orderId)).isEqualTo(1);
		assertThatThrownBy(() -> shop.cancel(site, orderId, Map.of())).hasMessageContaining("이미 취소");
	}

	@SuppressWarnings("unchecked")
	@Test void personalBankTransferNeedsDepositAccountAndConsent() {
		setUp(false);
		assertThatThrownBy(() -> shop.placeOrder(hq, orderBody("zz-test-key-0003", "PERSONAL", "BANK_TRANSFER", 1))).hasMessageContaining("입금 계좌");
		admin.saveSeller(new HashMap<>(Map.of("seller_name", "테스트 더채움", "biz_no", "1234567890", "deposit_bank_name", "국민은행",
				"deposit_account_no", "123-456-789", "deposit_account_holder", "더채움", "deposit_due_days", 2, "user_id", "TEST")));
		Map<String, Object> noConsent = orderBody("zz-test-key-0004", "PERSONAL", "BANK_TRANSFER", 1);
		noConsent.put("privacy_agreed", false);
		assertThatThrownBy(() -> shop.placeOrder(hq, noConsent)).hasMessageContaining("동의");
		assertThatThrownBy(() -> shop.placeOrder(hq, orderBody("zz-test-key-0005", "ACCOUNT", "CREDIT", 1))).hasMessageContaining("개인 구매만");
		assertThatThrownBy(() -> shop.placeOrder(hq, orderBody("zz-test-key-0006", "PERSONAL", "CREDIT", 1))).hasMessageContaining("후불은 거래처");

		long orderId = ((Number) shop.placeOrder(hq, orderBody("zz-test-key-0007", "PERSONAL", "BANK_TRANSFER", 1)).get("order_id")).longValue();
		var detail = shop.myOrder(hq, orderId);
		var payment = ((List<Map<String, Object>>) detail.get("payments")).get(0);
		assertThat(payment.get("status")).isEqualTo("WAITING_DEPOSIT");
		assertThat(payment.get("deposit_account_no")).isEqualTo("123-456-789");
		assertThat(payment.get("depositor_name")).isEqualTo("홍길동");
		assertThat(payment.get("deposit_due_at").toString()).endsWith("23:59");
		assertThat(new BigDecimal(payment.get("amount").toString())).isEqualByComparingTo("56000"); // 52000 + 4000
		assertThat(jdbc.queryForObject("SELECT phone_masked FROM the_full_order.tb_dist_customer WHERE login_user_id='zz_hq_buyer'", String.class)).isEqualTo("010-****-5678");
		assertThat(((Map<String, Object>) detail.get("order")).get("order_status")).isEqualTo("PENDING_PAYMENT");

		// 입금 확인된 주문(3단계 본사 처리 흉내) → 취소는 요청으로
		jdbc.update("UPDATE the_full_order.tb_dist_order SET order_status='PAID',payment_status='PAID',paid_amount=total_amount WHERE order_id=?", orderId);
		var result = shop.cancel(hq, orderId, Map.of("reason", "필요 없어짐"));
		assertThat(result.get("mode")).isEqualTo("REQUEST");
		assertThat(jdbc.queryForObject("SELECT status FROM the_full_order.tb_dist_claim WHERE order_id=?", String.class, orderId)).isEqualTo("REQUESTED");
		assertThat(((Map<String, Object>) shop.myOrder(hq, orderId).get("cancel")).get("mode")).isEqualTo("NONE");
	}
}
