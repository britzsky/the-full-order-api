package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 로컬 DB(조회만): 식단표 불러오기 후 저장 전, 거래처에 아직 없는 본사 메뉴를 눌러도 본사 레시피로 식자재 상세가 나온다.
 * 실행: RUN_LOCAL_DB_TESTS=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LocalHqMenuDetailDatabaseTest {
	@Autowired JdbcTemplate jdbc;
	@Autowired MenuService menus;

	@Test
	void showsHqRecipeForMenuTheAccountDoesNotHaveYet() {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT a.account_id,r.menu_id,COUNT(*) cnt
				  FROM (SELECT DISTINCT account_id FROM the_full_order.tb_account_menu_master) a
				  JOIN the_full_order.tb_recipe_detail r ON r.qty_num IS NOT NULL
				 WHERE NOT EXISTS(SELECT 1 FROM the_full_order.tb_account_menu_master m WHERE m.account_id=a.account_id AND m.menu_id=r.menu_id AND m.del_yn='N')
				 GROUP BY a.account_id,r.menu_id LIMIT 1""");
		if (rows.isEmpty()) return;
		Map<String, Object> row = rows.get(0);
		Map<String, Object> p = new HashMap<>(Map.of("account_id", row.get("account_id"), "menu_id", row.get("menu_id"), "servingQty", "1"));

		List<Map<String, Object>> detail = menus.AccountDetailList(p);
		assertEquals(((Number) row.get("cnt")).intValue(), detail.size(), "본사 레시피 줄 수만큼");
		assertTrue(detail.stream().allMatch(d -> "HQ".equals(d.get("recipe_source"))));
		System.out.println("[본사 메뉴 상세] " + row.get("account_id") + "/" + row.get("menu_id") + " " + detail.size() + "줄, 첫 줄 " + detail.get(0).get("ingredient_name") + " " + detail.get(0).get("required_qty") + detail.get(0).get("base_unit"));

		// 거래처 메뉴는 기존대로 거래처 레시피(표시 없음)
		List<Map<String, Object>> own = jdbc.queryForList("SELECT account_id,menu_id FROM the_full_order.tb_account_recipe_detail WHERE qty_num IS NOT NULL LIMIT 1");
		if (!own.isEmpty()) {
			List<Map<String, Object>> accountDetail = menus.AccountDetailList(new HashMap<>(own.get(0)));
			assertFalse(accountDetail.isEmpty());
			assertTrue(accountDetail.stream().noneMatch(d -> "HQ".equals(d.get("recipe_source"))));
		}
	}
}
