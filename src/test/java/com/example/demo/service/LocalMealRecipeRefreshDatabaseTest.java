package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 DB(끝나면 되돌림): 거래처 레시피 1인 사용량을 바꾸면 오늘 이후·사용 확정 전 식사만 필요량이 새 값으로, 지난 식사는 그대로.
 * 실행: RUN_LOCAL_DB_TESTS=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalMealRecipeRefreshDatabaseTest {
	@Autowired JdbcTemplate jdbc;
	@Autowired MealRecipeRefreshService refresh;

	private static final String REQ = "SELECT total_required_qty FROM the_full_order.tb_account_meal_ingredient_requirement WHERE meal_service_id=? AND menu_id=? AND ingredient_id=?";

	@Test
	void recalculatesOnlyFutureUnconfirmedMeals() {
		// 오늘 이후 사용 확정 전 식사에 들어 있고 필요량이 있는 메뉴·식재료 하나
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT s.account_id,r.meal_service_id,r.menu_id,r.ingredient_id,r.total_required_qty,s.planned_servings
				  FROM the_full_order.tb_account_meal_ingredient_requirement r
				  JOIN the_full_order.tb_account_meal_service s ON s.meal_service_id=r.meal_service_id
				 WHERE s.meal_date>=CURRENT_DATE AND s.status NOT IN ('COMPLETED','CANCELED') AND r.total_required_qty>0
				   AND NOT EXISTS(SELECT 1 FROM the_full_order.tb_account_meal_stock_usage u WHERE u.meal_service_id=s.meal_service_id AND u.reversed_at IS NULL)
				 ORDER BY s.meal_date LIMIT 1""");
		if (rows.isEmpty()) return;
		Map<String, Object> row = rows.get(0);
		Object account = row.get("account_id"), menu = row.get("menu_id"), ingredient = row.get("ingredient_id");
		BigDecimal before = (BigDecimal) row.get("total_required_qty");

		// 같은 메뉴·식재료가 든 지난 식사(있으면) — 바뀌면 안 된다
		List<Map<String, Object>> past = jdbc.queryForList("""
				SELECT r.meal_service_id,r.total_required_qty FROM the_full_order.tb_account_meal_ingredient_requirement r
				  JOIN the_full_order.tb_account_meal_service s ON s.meal_service_id=r.meal_service_id
				 WHERE s.account_id=? AND r.menu_id=? AND r.ingredient_id=? AND s.meal_date<CURRENT_DATE LIMIT 1""", account, menu, ingredient);

		// 1인 사용량 절반으로(예: 양파 30g → 15g)
		int changed = jdbc.update("""
				UPDATE the_full_order.tb_account_recipe_detail SET qty_base=qty_base/2,qty_per_person=qty_per_person/2,qty_num=qty_num/2
				 WHERE account_id=? AND menu_id=? AND ingredient_id=?""", account, menu, ingredient);
		assertTrue(changed > 0);

		int refreshed = refresh.refresh(account, menu);
		assertTrue(refreshed >= 1, "오늘 이후 식사가 다시 계산되어야 한다");
		BigDecimal after = jdbc.queryForObject(REQ, BigDecimal.class, row.get("meal_service_id"), menu, ingredient);
		assertEquals(0, before.divide(BigDecimal.valueOf(2)).compareTo(after), "필요량이 새 레시피(절반)로: " + before + " → " + after);
		System.out.println("[레시피 변경 반영] 다시 계산한 식사 " + refreshed + "끼, " + menu + "/" + ingredient + " " + before + " → " + after);

		for (Map<String, Object> p : past) {
			BigDecimal kept = jdbc.queryForObject(REQ, BigDecimal.class, p.get("meal_service_id"), menu, ingredient);
			assertEquals(0, ((BigDecimal) p.get("total_required_qty")).compareTo(kept), "지난 식사는 그때 양 그대로");
		}
	}
}
