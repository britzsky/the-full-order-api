package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 2026-10-22 개편 흐름을 로컬 DB(정본 DDL + 시드, 테스트 거래처 20250819193455)의 실제 SQL로 확인한다. 테스트마다 롤백.
 * 실행: RUN_LOCAL_DB_TESTS=true + 로컬 DB 접속 환경변수(SPRING_DATASOURCE_URL=localhost 등).
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalIngredientStockFlowDatabaseTest {
	static final String ACCOUNT = "20250819193455";
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper json;
	@Autowired ReceiptPostingService receipts;
	@Autowired MealPlanV2Service plans;
	@Autowired MealUsageService usage;
	@Autowired SupplierIntegrationService suppliers;
	@Autowired MealPlanTemplateService templates;
	@Autowired ShortageProcurementService shortages;
	@Autowired InventoryService inventory;
	@Autowired MenuService menus;

	long product(String code) {
		return jdbc.queryForObject("SELECT supplier_product_id FROM the_full_order.tb_supplier_ingredient_product WHERE supplier_item_code=?", Long.class, code);
	}

	BigDecimal stock(String ingredient) {
		return jdbc.queryForObject("SELECT COALESCE(SUM(current_base_qty),0) FROM the_full_order.tb_account_inventory_balance WHERE account_id=? AND ingredient_id=?",
				BigDecimal.class, ACCOUNT, ingredient);
	}

	/** 공급사 발주 1건(품목 1개). ingredient가 null이면 상품만 발주한 줄 */
	String order(String supplierCode, String itemCode, String ingredient, int qty) {
		String id = "TEST-" + UUID.randomUUID().toString().substring(0, 18);
		Long supplier = jdbc.queryForObject("SELECT supplier_id FROM the_full_order.tb_supplier WHERE supplier_code=?", Long.class, supplierCode);
		jdbc.update("INSERT INTO the_full_order.tb_account_purchase_order(purchase_order_id,account_id,supplier_id,sold_to,client_ord,requested_delivery_date,status,total_amount) VALUES(?,?,?,?,?,CURRENT_DATE,'ORDERED',0)",
				id, ACCOUNT, supplier, "S", id);
		var p = jdbc.queryForMap("SELECT supplier_product_id,product_name,base_qty,base_unit,order_unit FROM the_full_order.tb_supplier_ingredient_product WHERE supplier_item_code=?", itemCode);
		jdbc.update("INSERT INTO the_full_order.tb_account_purchase_order_item(purchase_order_id,client_ord_item,supplier_product_id,source_ingredient_id,supplier_item_code,product_name_snapshot,order_qty,order_unit,base_qty_per_order_unit,base_unit,unit_price_snapshot,line_amount,item_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'ORDERED')",
				id, "1", p.get("supplier_product_id"), ingredient, itemCode, p.get("product_name"), qty, p.get("order_unit"), p.get("base_qty"), p.get("base_unit"), 50000, 50000 * qty);
		return id;
	}

	Map<String, Object> receive(String orderId, int qty, String ingredient) {
		var payload = json.createObjectNode().put("account_id", ACCOUNT).put("purchase_order_id", orderId)
				.put("receipt_key", UUID.randomUUID().toString()).put("receipt_date", LocalDate.now().toString()).put("user_id", "TEST");
		var item = payload.putArray("items").addObject().put("client_ord_item", "1").put("received_qty", qty);
		if (ingredient != null) item.put("ingredient_id", ingredient);
		return receipts.receive(payload);
	}

	@Test void receiptsOfDifferentSuppliersGoIntoOneIngredientStock() {
		BigDecimal before = stock("TIG001");
		receive(order("TEST_WELSTORY", "TEST-W-001", "TIG001", 1), 1, null);
		receive(order("TEST_OURHOME", "TEST-A-001", "TIG001", 1), 1, null);
		assertThat(stock("TIG001")).isEqualByComparingTo(before.add(new BigDecimal("40000")));
		assertThat(jdbc.queryForList("SELECT product_name_snapshot FROM the_full_order.tb_account_inventory_lot WHERE account_id=? AND ingredient_id='TIG001' ORDER BY inventory_lot_id",
				String.class, ACCOUNT)).containsExactly("테스트 웰스토리 쌀", "테스트 아워홈 쌀");
		assertThat(jdbc.queryForList("SELECT reference_id FROM the_full_order.tb_account_inventory_movement m JOIN the_full_order.tb_goods_receipt_item g ON g.inventory_movement_id=m.movement_id WHERE m.account_id=? AND m.ingredient_id='TIG001' AND CAST(g.goods_receipt_item_id AS CHAR)=m.reference_id",
				String.class, ACCOUNT)).hasSize(2);
		// 같은 식재료 한 줄, 평균 단가 하나
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_account_inventory_balance WHERE account_id=? AND ingredient_id='TIG001'", Integer.class, ACCOUNT)).isEqualTo(1);
	}

	@Test void productOnlyLineChoosesIngredientAtReceipt() {
		String id = order("TEST_WELSTORY", "TEST-W-010", null, 2);
		assertThatThrownBy(() -> receive(id, 1, null)).hasMessageContaining("재고에 넣을 식재료 선택 요망");
		BigDecimal before = stock("TIG010");
		receive(id, 1, "TIG010");
		assertThat(stock("TIG010")).isEqualByComparingTo(before.add(new BigDecimal("10000")));
		assertThat(jdbc.queryForObject("SELECT source_ingredient_id FROM the_full_order.tb_account_purchase_order_item WHERE purchase_order_id=?", String.class, id)).isEqualTo("TIG010");
		// 나머지 1개는 이미 정한 식재료로 그대로 입고된다
		receive(id, 1, null);
		assertThat(stock("TIG010")).isEqualByComparingTo(before.add(new BigDecimal("20000")));
	}

	/** 본사 메뉴 하나(거래처 메뉴 아님): 감자(직접 입력 3원/g)·양배추(입고 평균)·돼지고기(재고 없음 → 본사 표준 단가) */
	String hqMenu(String id, int porkGrams) {
		jdbc.update("INSERT INTO the_full_order.tb_menu_master(menu_id,menu_name,food_type,menu_type,menu_gubun,meal_plan_type,calories_per_serving,del_yn) VALUES(?,?,1,0,3,0,500,'N')", id, "테스트 본사 메뉴 " + id);
		int seq = 1;
		for (var row : List.of(List.of("TIG009", 100), List.of("TIG014", 50), List.of("TIG004", porkGrams))) {
			jdbc.update("INSERT INTO the_full_order.tb_recipe_detail(recipe_id,menu_id,ingredient_id,ingredient_seq,ingredient_name_raw,qty_num,qty_unit,recipe_yield_servings,qty_base,base_unit,qty_per_person,review_flag) VALUES(?,?,?,?,?,?,'g',1,?,'g',?,0)",
					990001, id, row.get(0), seq++, row.get(0), row.get(1), row.get(1), row.get(1));
		}
		return id;
	}

	Map<String, Object> meal(LocalDate date, String menuId, boolean special) {
		Map<String, Object> meal = new HashMap<>();
		meal.put("meal_date", date.toString());
		meal.put("weekday", "월");
		meal.put("meal_slot", "2");
		meal.put("meal_slot_code", 2);
		meal.put("food_type", 1);
		meal.put("meal_plan_type", 0);
		if (special) { meal.put("special_yn", "Y"); meal.put("special_note", "테스트 특식"); }
		meal.put("menus", List.of(Map.of("menu_id", menuId, "menu_name", "테스트", "sort_order", 1, "menu_type", 0, "menu_gubun", 3, "food_type", 1)));
		return meal;
	}

	Map<String, Object> plan(String table, List<Map<String, Object>> meals) {
		Map<String, Object> body = new HashMap<>();
		body.put("table_id", table);
		body.put("account_id", ACCOUNT);
		body.put("table_year", 2099);
		body.put("table_month", 1);
		body.put("table_week", 1);
		body.put("meal_plan_type", 0);
		body.put("planned_servings", 10);
		body.put("user_id", "TEST");
		body.put("meals", meals);
		return plans.saveCompletePlan(body);
	}

	@Test void hqMenuIsCopiedToAccountAndCostsComeFromIngredientPrices() {
		String menu = hqMenu("TMENUX01", 30);
		LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
		plan("TBL-X01", List.of(meal(monday, menu, false)));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM the_full_order.tb_account_recipe_detail WHERE account_id=? AND menu_id=?", Integer.class, ACCOUNT, menu)).isEqualTo(3);
		var sources = new HashMap<String, String>();
		for (var row : jdbc.queryForList("SELECT r.ingredient_id,r.cost_source FROM the_full_order.tb_account_meal_ingredient_requirement r JOIN the_full_order.tb_account_meal_service s ON s.meal_service_id=r.meal_service_id WHERE s.table_id='TBL-X01'"))
			sources.put(row.get("ingredient_id").toString(), String.valueOf(row.get("cost_source")));
		assertThat(sources).containsEntry("TIG009", "MANUAL").containsEntry("TIG014", "RECEIPT").containsEntry("TIG004", "STANDARD");
	}

	@Test void weeklyBudgetWarnsOnlyAndKeepsSpecialOverageSeparate() {
		LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
		String normal = hqMenu("TMENUX02", 10);
		String expensive = hqMenu("TMENUX03", 2000); // 돼지고기 2kg/인 → 식단가 초과(저장은 막지 않음)
		plan("TBL-X02", List.of(meal(monday, normal, false), meal(monday.plusDays(1), expensive, true)));
		var summary = plans.budgetSummary(new HashMap<>(Map.of("table_id", "TBL-X02", "account_id", ACCOUNT)));
		assertThat((List<?>) summary.get("meals")).hasSize(2);
		assertThat(new BigDecimal(summary.get("special_over_total").toString())).isPositive();
		BigDecimal budget = new BigDecimal(summary.get("budget_total").toString());
		BigDecimal cost = new BigDecimal(summary.get("cost_total").toString());
		assertThat(cost).as("특식 초과분은 주 합계에서 뺀다").isLessThanOrEqualTo(budget);
		assertThat(summary.get("message").toString()).contains("특식 초과");
	}

	@Test void usageConsumesIngredientStockAcrossSupplierLots() {
		receive(order("TEST_WELSTORY", "TEST-W-001", "TIG001", 1), 1, null);
		receive(order("TEST_OURHOME", "TEST-A-001", "TIG001", 1), 1, null);
		jdbc.update("INSERT INTO the_full_order.tb_menu_master(menu_id,menu_name,food_type,menu_type,menu_gubun,meal_plan_type,del_yn) VALUES('TMENUX04','테스트 밥',1,0,0,0,'N')");
		jdbc.update("INSERT INTO the_full_order.tb_recipe_detail(recipe_id,menu_id,ingredient_id,ingredient_seq,qty_num,qty_unit,recipe_yield_servings,qty_base,base_unit,qty_per_person,review_flag) VALUES(990004,'TMENUX04','TIG001',1,2500,'g',1,2500,'g',2500,0)");
		plan("TBL-X04", List.of(meal(LocalDate.now(), "TMENUX04", false)));
		Long service = jdbc.queryForObject("SELECT meal_service_id FROM the_full_order.tb_account_meal_service WHERE table_id='TBL-X04'", Long.class);
		BigDecimal before = stock("TIG001");
		usage.complete(new HashMap<>(Map.of("meal_service_id", service, "account_id", ACCOUNT, "actual_servings", 10, "user_id", "TEST")));
		assertThat(stock("TIG001")).isEqualByComparingTo(before.subtract(new BigDecimal("25000")));
		// 입고일순: 웰스토리 LOT 20kg 전부 + 아워홈 LOT 5kg. 사용 기록에 상품명이 남는다
		assertThat(jdbc.queryForList("SELECT product_name_snapshot FROM the_full_order.tb_account_meal_stock_usage WHERE meal_service_id=? ORDER BY inventory_lot_id", String.class, service))
				.containsExactly("테스트 웰스토리 쌀", "테스트 아워홈 쌀");
	}

	@Test void favoriteProductsComeFirstInSearchAndFavoriteMenusFeedOrdering() {
		long onion = product("TEST-W-010");
		Map<String, Object> fav = new HashMap<>(Map.of("supplier_product_id", onion, "favorite", "Y", "favorite_owner_type", "USER", "favorite_owner_id", "hq-test"));
		suppliers.favorite(fav);
		Map<String, Object> search = new HashMap<>(Map.of("account_id", ACCOUNT, "keyword", "양파", "favorite_owner_type", "USER", "favorite_owner_id", "hq-test"));
		var offers = suppliers.offers(search);
		assertThat(offers).isNotEmpty();
		assertThat(offers.get(0).get("supplier_product_id").toString()).isEqualTo(String.valueOf(onion));
		assertThat(offers.get(0).get("favorite_yn")).isEqualTo("Y");
		// 나만의 메뉴(시드: 거래처 단위 TMENU001~005)의 식재료로 발주: 수량 직접 입력 줄 + 이름으로 찾은 상품 후보
		Map<String, Object> criteria = new HashMap<>(Map.of("account_id", ACCOUNT, "source", "FAVORITE_MENU",
				"favorite_owner_type", "ACCOUNT", "favorite_owner_id", ACCOUNT));
		@SuppressWarnings("unchecked")
		var rows = (List<Map<String, Object>>) shortages.shortages(criteria).get("ingredients");
		assertThat(rows).isNotEmpty().allMatch(r -> "MANUAL".equals(r.get("quantity_basis")));
		assertThat(rows).anyMatch(r -> !((List<?>) r.get("offers")).isEmpty());
	}

	@Test void templatesMatchTheAccountPriceTier() {
		Map<String, Object> saved = new HashMap<>(Map.of("price_tier", 13000, "meal_plan_type", 0, "template_seq", 2, "template_name", "테스트 13,000원 B", "user_id", "TEST",
				"items", List.of(Map.of("day_index", 1, "meal_slot_code", 2, "menu_id", "TMENU001"), Map.of("day_index", 1, "meal_slot_code", 2, "menu_id", "TMENU002"))));
		templates.save(saved);
		var forAccount = templates.forAccount(new HashMap<>(Map.of("account_id", ACCOUNT, "meal_plan_type", "0")));
		assertThat(forAccount.get("price_tier")).isEqualTo(13000); // 4,300 × 3 = 12,900원 → 13,000원 금액대
		assertThat((List<?>) forAccount.get("templates")).hasSize(2);
		var detail = templates.detail(new HashMap<>(Map.of("meal_plan_template_id", saved.get("meal_plan_template_id"))));
		assertThat((List<?>) detail.get("items")).hasSize(2);
		assertThatThrownBy(() -> templates.save(new HashMap<>(Map.of("price_tier", 13000, "meal_plan_type", 0, "template_seq", 4, "template_name", "x",
				"items", List.of(Map.of("day_index", 1, "meal_slot_code", 2, "menu_id", "TMENU001")))))).hasMessageContaining("3개");
	}

	@Test void inventoryListsIngredientRowsWithRecentProduct() {
		var rows = inventory.AccountInventoryList(new HashMap<>(Map.of("account_id", ACCOUNT)));
		var cabbage = rows.stream().filter(r -> "TIG014".equals(r.get("ingredient_id"))).findFirst().orElseThrow();
		assertThat(new BigDecimal(cabbage.get("current_base_qty").toString())).isEqualByComparingTo("21000");
		assertThat(cabbage.get("recent_product_name")).isNotNull();
		var detail = menus.AccountDetailList(new HashMap<>(Map.of("account_id", ACCOUNT, "menu_id", "TMENU001", "servingQty", 1)));
		assertThat(detail).isNotEmpty().allMatch(r -> r.get("cost_source") != null);
	}
}
