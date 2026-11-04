package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 더채움 유통 협력사 관리(2026-11-02)를 로컬 DB의 실제 SQL로 확인한다(tb_dist_* 테이블이 있어야 함). 테스트마다 롤백.
 * 실행: RUN_LOCAL_DB_TESTS=true + 로컬 DB 접속 환경변수.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalDistAdminDatabaseTest {
	@Autowired JdbcTemplate jdbc;
	@Autowired DistAdminService service;

	long setUpProduct() {
		if (service.seller().get("seller") == null)
			service.saveSeller(new HashMap<>(Map.of("seller_name", "테스트 더채움", "biz_no", "123-45-67890", "user_id", "TEST")));
		long partner = ((Number) service.savePartner(new HashMap<>(Map.of("partner_code", "zz_test_wando", "partner_name", "테스트 완도수산",
				"partner_type", "DROP_SHIPPER", "order_cutoff_time", "14:00", "user_id", "TEST"))).get("partner_id")).longValue();
		long category = ((Number) service.saveCategory(new HashMap<>(Map.of("category_code", "ZZ_SEAFOOD", "category_name", "테스트 수산",
				"user_id", "TEST"))).get("category_id")).longValue();
		Map<String, Object> body = new HashMap<>(Map.of("partner_id", partner, "category_id", category, "product_name", "테스트 활전복",
				"option_name", "1kg(10~12미)", "fulfillment_type", "DROP_SHIP", "tax_type", "TAX_FREE", "storage_type", "CHILLED", "user_id", "TEST"));
		body.put("account_price", "45000");
		body.put("personal_price", "52000");
		body.put("unit_cost", "38000");
		return ((Number) service.saveProduct(body).get("product_id")).longValue();
	}

	@SuppressWarnings("unchecked")
	@Test void productLifecycleWithPricesAndHistory() {
		long id = setUpProduct();
		Map<String, Object> detail = service.product(id);
		Map<String, Object> product = (Map<String, Object>) detail.get("product");
		assertThat(product.get("product_code").toString()).matches("P\\d{6}-\\d{3}");
		assertThat(product.get("sale_status")).isEqualTo("DRAFT");
		assertThat(product.get("approval_status")).isEqualTo("APPROVED");
		assertThat((List<?>) detail.get("sale_prices")).hasSize(2);
		assertThat((List<?>) detail.get("cost_prices")).hasSize(1);
		assertThat(((List<Map<String, Object>>) detail.get("history")).get(0).get("request_type")).isEqualTo("CREATE");

		// 목록에 현재가·매입가가 보인다
		var rows = (List<Map<String, Object>>) service.products(Map.of("keyword", "테스트 활전복")).get("products");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("account_price").toString()).startsWith("45000");
		assertThat(rows.get(0).get("unit_cost").toString()).startsWith("38000");

		// 수정은 바뀐 값만 이력
		Map<String, Object> edit = new HashMap<>(product);
		edit.put("product_name", "테스트 활전복(특)");
		edit.put("user_id", "TEST");
		service.saveProduct(edit);
		var history = (List<Map<String, Object>>) service.product(id).get("history");
		assertThat(history.get(0).get("request_type")).isEqualTo("UPDATE");
		assertThat(history.get(0).get("after_json").toString()).contains("테스트 활전복(특)").doesNotContain("option_name");

		// 판매 시작 → 판매가 변경은 이전 가격을 닫는다(현재가 1개)
		service.changeStatus(new HashMap<>(Map.of("product_id", id, "sale_status", "ON_SALE", "user_id", "TEST")));
		service.addSalePrice(new HashMap<>(Map.of("product_id", id, "price_type", "ACCOUNT", "unit_price", "43000")), "TEST");
		var prices = jdbc.queryForList("SELECT unit_price,end_at FROM the_full_order.tb_dist_sale_price_history WHERE product_id=? AND price_type='ACCOUNT' ORDER BY sale_price_id", id);
		assertThat(prices).hasSize(2);
		assertThat(prices.get(0).get("end_at")).isNotNull();
		assertThat(prices.get(1).get("end_at")).isNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_dist_sale_price_history WHERE product_id=? AND price_type='ACCOUNT' AND start_at<=NOW() AND (end_at IS NULL OR end_at>NOW())", Integer.class, id)).isEqualTo(1);

		// 매입가 변경: 내일부터 → 오늘 행은 오늘로 닫힘
		String tomorrow = jdbc.queryForObject("SELECT DATE_FORMAT(CURDATE()+INTERVAL 1 DAY,'%Y-%m-%d')", String.class);
		service.addCostPrice(new HashMap<>(Map.of("product_id", id, "unit_cost", "39000", "start_date", tomorrow)), "TEST");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_dist_cost_price_history WHERE product_id=? AND end_date=CURDATE()", Integer.class, id)).isEqualTo(1);
	}

	@Test void availabilityPeriodsDoNotOverlap() {
		long id = setUpProduct();
		service.saveAvailability(new HashMap<>(Map.of("product_id", id, "available_from", "2026-11-01", "available_to", "2026-11-30", "available_qty", 100, "user_id", "TEST")));
		assertThatThrownBy(() -> service.saveAvailability(new HashMap<>(Map.of("product_id", id, "available_from", "2026-11-15", "user_id", "TEST"))))
				.hasMessageContaining("겹치는");
		service.saveAvailability(new HashMap<>(Map.of("product_id", id, "available_from", "2026-12-01", "status", "LIMITED", "user_id", "TEST")));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_dist_product_availability WHERE product_id=?", Integer.class, id)).isEqualTo(2);
	}

	@Test void imagesAndDuplicateCodes() {
		long id = setUpProduct();
		service.addImage(new HashMap<>(Map.of("product_id", id, "image_url", "https://example.com/a.jpg", "user_id", "TEST")));
		service.addImage(new HashMap<>(Map.of("product_id", id, "image_url", "https://example.com/b.jpg", "image_type", "DETAIL", "user_id", "TEST")));
		var images = jdbc.queryForList("SELECT product_image_id,sort_order FROM the_full_order.tb_dist_product_image WHERE product_id=? ORDER BY sort_order", id);
		assertThat(images).extracting(r -> ((Number) r.get("sort_order")).intValue()).containsExactly(0, 1);
		service.deleteImage(id, images.get(0).get("product_image_id"), "TEST");
		assertThatThrownBy(() -> service.savePartner(new HashMap<>(Map.of("partner_code", "ZZ_TEST_WANDO", "partner_name", "중복", "user_id", "TEST"))))
				.hasMessageContaining("같은 협력사 코드");
	}

	/** 2026-11-04 상세·표시사항: 편집기 HTML은 걸러서, 표시사항 JSON은 MySQL JSON 칸에 그대로 — 같은 내용으로 다시 저장하면 이력이 안 생긴다 */
	@SuppressWarnings("unchecked")
	@Test void contentHtmlAndProductInfoRoundTrip() {
		long id = setUpProduct();
		Map<String, Object> edit = new HashMap<>((Map<String, Object>) service.product(id).get("product"));
		edit.put("description_html", "<h2 style=\"text-align:center\">완도 활전복</h2><p onclick=\"x()\">살이 <strong>단단</strong>합니다</p><script>1</script>");
		edit.put("food_label_json", "{\"items\":[{\"key\":\"product_name\",\"label\":\"제품명\",\"value\":\"활전복\"},{\"key\":\"storage\",\"label\":\"보관방법\",\"value\":\"냉장\"}]}");
		edit.put("notice_json", "{\"type\":\"AGRI_FOOD\",\"type_name\":\"농수축산물\",\"items\":[{\"key\":\"origin\",\"label\":\"원산지\",\"value\":\"국산(완도)\"}]}");
		edit.put("user_id", "TEST");
		service.saveProduct(edit);
		Map<String, Object> saved = (Map<String, Object>) service.product(id).get("product");
		assertThat(saved.get("description_html")).isEqualTo("<h2 style=\"text-align: center\">완도 활전복</h2><p>살이 <strong>단단</strong>합니다</p>");
		assertThat(saved.get("food_label_json").toString()).contains("활전복", "보관방법");
		assertThat(saved.get("notice_json").toString()).contains("AGRI_FOOD", "국산(완도)");
		int histories = ((List<?>) service.product(id).get("history")).size();

		Map<String, Object> again = new HashMap<>(saved);
		again.put("user_id", "TEST");
		service.saveProduct(again);
		assertThat((List<?>) service.product(id).get("history")).hasSize(histories);

		again.put("notice_json", "");
		service.saveProduct(again);
		assertThat(((Map<String, Object>) service.product(id).get("product")).get("notice_json")).isNull();
		assertThat(((List<Map<String, Object>>) service.product(id).get("history")).get(0).get("after_json").toString()).contains("notice_json");
	}
}
