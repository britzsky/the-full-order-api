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
 * 더채움 유통 3단계 본사 주문 처리(2026-11-02)를 로컬 DB의 실제 SQL로 확인한다. 테스트마다 롤백. dist.pii.key 필요.
 * 실행: RUN_LOCAL_DB_TESTS=true + 로컬 DB 접속 환경변수.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalDistOrderAdminDatabaseTest {
	@Autowired JdbcTemplate jdbc;
	@Autowired DistAdminService admin;
	@Autowired DistShopService shop;
	@Autowired DistOrderAdminService orders;

	WorkspaceUser hq = new WorkspaceUser("zz_hq_buyer", "2", "");
	WorkspaceUser site;
	long wando, hachu, abalone, snack;

	void setUp() {
		String account = jdbc.queryForObject("SELECT account_id FROM the_full.tb_account ORDER BY account_id LIMIT 1", String.class);
		site = new WorkspaceUser("zz_site_buyer", "3", account);
		admin.saveSeller(new HashMap<>(Map.of("seller_name", "테스트 더채움", "biz_no", "1234567890", "deposit_bank_name", "국민은행",
				"deposit_account_no", "123-456-789", "deposit_account_holder", "더채움", "user_id", "TEST")));
		wando = ((Number) admin.savePartner(new HashMap<>(Map.of("partner_code", "ZZ_OA_WANDO", "partner_name", "테스트 완도", "partner_type", "DROP_SHIPPER", "user_id", "TEST"))).get("partner_id")).longValue();
		hachu = ((Number) admin.savePartner(new HashMap<>(Map.of("partner_code", "ZZ_OA_HACHU", "partner_name", "테스트 하츄핑", "partner_type", "SUPPLIER", "user_id", "TEST"))).get("partner_id")).longValue();
		abalone = product(wando, "DROP_SHIP", "테스트 활전복", "45000", "52000", "38000");
		snack = product(hachu, "OWN_STOCK", "테스트 간식", "10000", "12000", "6000");
		String today = jdbc.queryForObject("SELECT DATE_FORMAT(CURDATE(),'%Y-%m-%d')", String.class);
		admin.saveAvailability(new HashMap<>(Map.of("product_id", abalone, "available_from", today, "available_qty", 10, "user_id", "TEST")));
	}

	long product(long partner, String type, String name, String accountPrice, String personalPrice, String cost) {
		Map<String, Object> body = new HashMap<>(Map.of("partner_id", partner, "product_name", name, "fulfillment_type", type, "user_id", "TEST"));
		body.put("account_price", accountPrice);
		body.put("personal_price", personalPrice);
		body.put("unit_cost", cost);
		long id = ((Number) admin.saveProduct(body).get("product_id")).longValue();
		admin.changeStatus(new HashMap<>(Map.of("product_id", id, "sale_status", "ON_SALE", "user_id", "TEST")));
		return id;
	}

	long order(WorkspaceUser user, String key, String buyerType, String method, int abaloneQty, int snackQty) {
		Map<String, Object> body = new HashMap<>();
		body.put("client_request_key", key);
		body.put("buyer_type", buyerType);
		body.put("payment_method", method);
		List<Map<String, Object>> items = new ArrayList<>();
		if (abaloneQty > 0) items.add(Map.of("product_id", abalone, "qty", abaloneQty));
		if (snackQty > 0) items.add(Map.of("product_id", snack, "qty", snackQty));
		body.put("items", items);
		body.put("orderer", Map.of("name", "홍길동", "phone", "01012345678"));
		body.put("recipient", Map.of("name", "김수령", "phone", "01098765432", "zipcode", "12345", "address1", "서울시 테스트구 1"));
		body.put("privacy_agreed", true);
		return ((Number) shop.placeOrder(user, body).get("order_id")).longValue();
	}

	String status(long orderId) {
		return jdbc.queryForObject("SELECT order_status FROM the_full_order.tb_dist_order WHERE order_id=?", String.class, orderId);
	}

	Map<String, Object> body(Object... pairs) {
		Map<String, Object> map = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
		map.put("user_id", "TEST_HQ");
		return map;
	}

	@SuppressWarnings("unchecked")
	@Test void depositPurchaseOrderShipmentsAndDelivery() {
		setUp();
		long id = order(hq, "zz-oa-key-0001", "PERSONAL", "BANK_TRANSFER", 3, 2);
		BigDecimal total = jdbc.queryForObject("SELECT total_amount FROM the_full_order.tb_dist_order WHERE order_id=?", BigDecimal.class, id);

		var detail = orders.order(id);
		assertThat(((Map<String, Object>) detail.get("order")).get("orderer_name")).isEqualTo("홍길동");
		assertThat(((Map<String, Object>) detail.get("recipient")).get("address1")).isEqualTo("서울시 테스트구 1");
		assertThat(((Map<String, Object>) detail.get("actions")).get("confirm_deposit")).isEqualTo(true);
		assertThat((List<Map<String, Object>>) orders.orders(Map.of("work", "need_deposit", "keyword", "테스트 활전복")).get("orders")).extracting(r -> ((Number) r.get("order_id")).longValue()).contains(id);

		assertThatThrownBy(() -> orders.createPurchaseOrder(id, body("partner_id", wando))).hasMessageContaining("입금 확인 전");
		assertThatThrownBy(() -> orders.confirmDeposit(id, body("amount", "1000"))).hasMessageContaining("다릅니다");
		orders.confirmDeposit(id, body("amount", total.toPlainString(), "depositor_name", "홍길동"));
		assertThat(status(id)).isEqualTo("PAID");
		assertThat(jdbc.queryForObject("SELECT status FROM the_full_order.tb_dist_payment WHERE order_id=?", String.class, id)).isEqualTo("PAID");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_dist_payment_transaction t JOIN the_full_order.tb_dist_payment p ON p.payment_id=t.payment_id WHERE p.order_id=? AND t.tx_type='DEPOSIT'", Integer.class, id)).isEqualTo(1);

		long poId = ((Number) orders.createPurchaseOrder(id, body("partner_id", wando, "expected_date", "2026-11-04")).get("purchase_order_id")).longValue();
		assertThat(status(id)).isEqualTo("PREPARING");
		assertThat(jdbc.queryForObject("SELECT total_amount FROM the_full_order.tb_dist_purchase_order WHERE purchase_order_id=?", BigDecimal.class, poId)).isEqualByComparingTo("114000");
		assertThatThrownBy(() -> orders.createPurchaseOrder(id, body("partner_id", wando))).hasMessageContaining("발주할 직송 상품이 없습니다");
		orders.confirmPurchaseOrder(poId, body());
		orders.prepareOwnStock(id, body());

		long abaloneItem = jdbc.queryForObject("SELECT order_item_id FROM the_full_order.tb_dist_order_item WHERE order_id=? AND product_id=?", Long.class, id, abalone);
		long snackItem = jdbc.queryForObject("SELECT order_item_id FROM the_full_order.tb_dist_order_item WHERE order_id=? AND product_id=?", Long.class, id, snack);
		assertThatThrownBy(() -> orders.addShipment(id, body("carrier_code", "CJ", "tracking_no", "900000000001",
				"items", List.of(Map.of("order_item_id", abaloneItem, "qty", 1), Map.of("order_item_id", snackItem, "qty", 1))))).hasMessageContaining("같은 출고지");
		assertThatThrownBy(() -> orders.addShipment(id, body("carrier_code", "CJ", "tracking_no", "900000000001", "items", List.of(Map.of("order_item_id", abaloneItem, "qty", 4)))))
				.hasMessageContaining("남은 출고 수량은 3개");
		orders.addShipment(id, body("carrier_code", "CJ", "tracking_no", "900000000001", "items", List.of(Map.of("order_item_id", abaloneItem, "qty", 2))));
		assertThat(status(id)).isEqualTo("PARTIALLY_SHIPPED");
		assertThatThrownBy(() -> orders.addShipment(id, body("carrier_code", "CJ", "tracking_no", "900000000001", "items", List.of(Map.of("order_item_id", abaloneItem, "qty", 1)))))
				.hasMessageContaining("이미 등록된 송장");
		assertThatThrownBy(() -> orders.cancelByHq(id, body("reason_code", "OUT_OF_STOCK"))).hasMessageContaining("이미 출고");
		orders.addShipment(id, body("carrier_code", "CJ", "tracking_no", "900000000002", "items", List.of(Map.of("order_item_id", abaloneItem, "qty", 1))));
		orders.addShipment(id, body("carrier_code", "EPOST", "tracking_no", "900000000003", "items", List.of(Map.of("order_item_id", snackItem, "qty", 2))));
		assertThat(status(id)).isEqualTo("SHIPPED");
		assertThat(jdbc.queryForObject("SELECT status FROM the_full_order.tb_dist_purchase_order WHERE purchase_order_id=?", String.class, poId)).isEqualTo("COMPLETED");

		// 본인 화면에서도 송장·조회 링크가 보인다
		var mine = shop.myOrder(hq, id);
		var shipments = (List<Map<String, Object>>) mine.get("shipments");
		assertThat(shipments).hasSize(3);
		assertThat(shipments.get(0).get("tracking_url").toString()).contains("900000000001");

		List<Long> shipmentIds = jdbc.queryForList("SELECT shipment_id FROM the_full_order.tb_dist_shipment WHERE order_id=? ORDER BY shipment_id", Long.class, id);
		orders.markDelivered(shipmentIds.get(0), body());
		orders.markDelivered(shipmentIds.get(1), body());
		assertThat(status(id)).isEqualTo("SHIPPED");
		orders.markDelivered(shipmentIds.get(2), body());
		assertThat(status(id)).isEqualTo("DELIVERED");
		assertThatThrownBy(() -> orders.markDelivered(shipmentIds.get(2), body())).hasMessageContaining("이미 끝난");
	}

	@SuppressWarnings("unchecked")
	@Test void cancelRequestRefundRejectAndHqCancel() {
		setUp();
		// 입금 후 고객 취소 요청 → 환불 정보 없으면 거절, 있으면 환불 기록 + 취소
		long paidOrder = order(hq, "zz-oa-key-0002", "PERSONAL", "BANK_TRANSFER", 2, 0);
		BigDecimal total = jdbc.queryForObject("SELECT total_amount FROM the_full_order.tb_dist_order WHERE order_id=?", BigDecimal.class, paidOrder);
		orders.confirmDeposit(paidOrder, body());
		assertThat(shop.cancel(hq, paidOrder, Map.of("reason", "필요 없어짐")).get("mode")).isEqualTo("REQUEST");
		long claim = jdbc.queryForObject("SELECT claim_id FROM the_full_order.tb_dist_claim WHERE order_id=?", Long.class, paidOrder);
		assertThat(((Map<String, Object>) orders.order(paidOrder).get("actions")).get("cancel")).isEqualTo(false);
		assertThatThrownBy(() -> orders.approveClaim(claim, body())).hasMessageContaining("환불한 은행");
		orders.approveClaim(claim, body("refund", Map.of("bank_name", "신한은행", "account_no", "110-123-456789", "holder", "홍길동")));
		assertThat(status(paidOrder)).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT payment_status FROM the_full_order.tb_dist_order WHERE order_id=?", String.class, paidOrder)).isEqualTo("REFUNDED");
		assertThat(jdbc.queryForObject("SELECT refunded_amount FROM the_full_order.tb_dist_order WHERE order_id=?", BigDecimal.class, paidOrder)).isEqualByComparingTo(total);
		String refundAccount = jdbc.queryForObject("SELECT t.refund_account_no_enc FROM the_full_order.tb_dist_payment_transaction t JOIN the_full_order.tb_dist_payment p ON p.payment_id=t.payment_id WHERE p.order_id=? AND t.tx_type='REFUND'", String.class, paidOrder);
		assertThat(refundAccount).startsWith("v1:").doesNotContain("110-123");
		assertThat(jdbc.queryForObject("SELECT status FROM the_full_order.tb_dist_claim WHERE claim_id=?", String.class, claim)).isEqualTo("COMPLETED");
		assertThat(jdbc.queryForObject("SELECT reserved_qty FROM the_full_order.tb_dist_product_availability WHERE product_id=?", BigDecimal.class, abalone)).isEqualByComparingTo("0");

		// 취소 요청 거절 → 주문은 그대로, 주문자 화면에 거절 사유
		long second = order(hq, "zz-oa-key-0003", "PERSONAL", "BANK_TRANSFER", 1, 0);
		orders.confirmDeposit(second, body());
		shop.cancel(hq, second, Map.of());
		long claim2 = jdbc.queryForObject("SELECT claim_id FROM the_full_order.tb_dist_claim WHERE order_id=?", Long.class, second);
		assertThatThrownBy(() -> orders.rejectClaim(claim2, body())).hasMessageContaining("거절 사유");
		orders.rejectClaim(claim2, body("reason", "이미 산지에서 출발했습니다"));
		assertThat(status(second)).isEqualTo("PAID");
		assertThat(((List<Map<String, Object>>) shop.myOrder(hq, second).get("claims")).get(0).get("reject_reason")).isEqualTo("이미 산지에서 출발했습니다");
		assertThatThrownBy(() -> orders.approveClaim(claim2, body())).hasMessageContaining("이미 처리된");

		// 후불 주문을 본사가 품절로 취소(돈이 오가지 않아 환불 없음)
		long credit = order(site, "zz-oa-key-0004", "ACCOUNT", "CREDIT", 1, 1);
		orders.createPurchaseOrder(credit, body("partner_id", wando));
		orders.cancelByHq(credit, body("reason_code", "OUT_OF_STOCK", "reason", "산지 출하 중단"));
		assertThat(status(credit)).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT payment_status FROM the_full_order.tb_dist_order WHERE order_id=?", String.class, credit)).isEqualTo("NONE");
		assertThat(jdbc.queryForObject("SELECT status FROM the_full_order.tb_dist_purchase_order WHERE order_id=?", String.class, credit)).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_dist_order_item WHERE order_id=? AND item_status<>'CANCELED'", Integer.class, credit)).isZero();
	}

	@SuppressWarnings("unchecked")
	@Test void accountProfileControlsCredit() {
		setUp();
		String account = site.accountId();
		orders.saveAccountProfile(body("account_id", account, "payment_terms", "MONTHLY", "credit_limit", "50000", "default_address1", "기본 주소 1"));
		var context = shop.context(site);
		assertThat(((Map<String, Object>) context.get("account_defaults")).get("address1")).isEqualTo("기본 주소 1");
		assertThatThrownBy(() -> order(site, "zz-oa-key-0005", "ACCOUNT", "CREDIT", 2, 0)).hasMessageContaining("한도");
		orders.saveAccountProfile(body("account_id", account, "payment_terms", "PREPAID"));
		assertThatThrownBy(() -> order(site, "zz-oa-key-0006", "ACCOUNT", "CREDIT", 1, 0)).hasMessageContaining("선결제");
		orders.saveAccountProfile(body("account_id", account, "order_enabled_yn", "N"));
		assertThatThrownBy(() -> order(site, "zz-oa-key-0007", "ACCOUNT", "BANK_TRANSFER", 1, 0)).hasMessageContaining("막혀");
		var list = (List<Map<String, Object>>) orders.accountProfiles(account, true).get("accounts");
		assertThat(list).extracting(r -> r.get("account_id")).contains(account);
		assertThatThrownBy(() -> orders.saveAccountProfile(body("account_id", "NO_SUCH_ACCOUNT"))).hasMessageContaining("거래처를 골라");
	}
}
