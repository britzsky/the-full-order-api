package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * 유통 테스트 데이터(ddl-source/order_insert_distribution_local.txt)가 더채움몰·주문 흐름과 맞는지 확인. seed가 없으면 건너뜀. 롤백.
 * 실행: RUN_LOCAL_DB_TESTS=true + 로컬 DB 접속 환경변수.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalDistSeedDataTest {
	static final String ACCOUNT = "20250819193455";
	@Autowired JdbcTemplate jdbc;
	@Autowired DistShopService shop;

	@SuppressWarnings("unchecked")
	@Test void seedProductsShowExpectedPricesPerBuyer() {
		assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_dist_product WHERE user_id='SEED'", Integer.class) > 0, "유통 seed 없음");
		WorkspaceUser site = new WorkspaceUser("zz_seed_site", "3", ACCOUNT);
		WorkspaceUser hq = new WorkspaceUser("zz_seed_hq", "2", "");
		var account = (List<Map<String, Object>>) shop.products(site, Map.of()).get("products");
		var personal = (List<Map<String, Object>>) shop.products(hq, Map.of()).get("products");
		Map<String, BigDecimal> accountPrice = new HashMap<>(), personalPrice = new HashMap<>();
		account.forEach(row -> accountPrice.put((String) row.get("product_code"), (BigDecimal) row.get("unit_price")));
		personal.forEach(row -> personalPrice.put((String) row.get("product_code"), (BigDecimal) row.get("unit_price")));

		// 거래처 전용가(43,000) > 공통 거래처가(45,000), 개인은 개인가
		assertThat(accountPrice.get("WD-AB-1K")).isEqualByComparingTo("43000");
		assertThat(personalPrice.get("WD-AB-1K")).isEqualByComparingTo("52000");
		// 행사가가 공통가보다 우선
		assertThat(accountPrice.get("WD-JANG-500")).isEqualByComparingTo("29000");
		assertThat(personalPrice.get("WD-JANG-500")).isEqualByComparingTo("29000");
		// 거래처 전용 상품·사내 소진·작성중·일시중지
		assertThat(accountPrice).containsKey("HC-DRINK-24");
		assertThat(personalPrice).doesNotContainKey("HC-DRINK-24");
		assertThat(accountPrice).doesNotContainKeys("HC-SPECIAL", "JJ-HALLABONG-3K", "WD-GIFT-SET");
		// 품절은 보이되 주문 불가, 기간 한정 감귤은 판매 중
		var soldOut = account.stream().filter(row -> "WD-MIYEOK-200".equals(row.get("product_code"))).findFirst().orElseThrow();
		assertThat(soldOut.get("orderable")).isEqualTo(false);
		assertThat(accountPrice).containsKey("JJ-TANGERINE-5K");
		assertThat(account.stream().filter(row -> "WD-AB-2K".equals(row.get("product_code"))).findFirst().orElseThrow().get("remaining_qty"))
				.isEqualTo(new BigDecimal("20.000"));

		// 장바구니: 완도 활전복·전복장(합포장) + 하츄핑 간식(합포장)·음료(따로 포장)
		var quote = shop.quote(site, Map.of("buyer_type", "ACCOUNT", "items", List.of(
				Map.of("product_id", 1, "qty", 1), Map.of("product_id", 3, "qty", 1), Map.of("product_id", 5, "qty", 2), Map.of("product_id", 7, "qty", 2))));
		assertThat((BigDecimal) quote.get("item_amount")).isEqualByComparingTo("140000"); // 43000+29000+30000+38000
		// 완도 4,000 + 하츄핑 68,000원 ≥ 무료 기준 50,000 → 합포장분 0 + 따로 포장 음료 3,500×2
		assertThat((BigDecimal) quote.get("shipping_fee")).isEqualByComparingTo("11000");
		var credit = (Map<String, Object>) quote.get("credit");
		assertThat(credit.get("allowed")).isEqualTo(true);
		var context = shop.context(site);
		assertThat(((Map<String, Object>) context.get("account_defaults")).get("recipient_name")).isEqualTo("한결 영양사");
		assertThat(((Map<String, Object>) context.get("deposit")).get("bank_name")).isEqualTo("국민은행");
	}
}
