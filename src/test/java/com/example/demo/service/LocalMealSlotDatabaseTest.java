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

/**
 * 본사 끼니 종류 추가(2026-10-23)를 로컬 DB의 실제 SQL·단가 뷰로 확인한다. 테스트마다 롤백.
 * 실행: RUN_LOCAL_DB_TESTS=true + 로컬 DB 접속 환경변수.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalMealSlotDatabaseTest {
	static final String ACCOUNT = "20250819193455";
	@Autowired JdbcTemplate jdbc;
	@Autowired MealSlotService slots;
	@Autowired AccountService accounts;
	@Autowired MealPlanTemplateService templates;
	@Autowired org.mybatis.spring.SqlSessionTemplate sql;

	List<Integer> selected() {
		return jdbc.queryForList("SELECT meal_slot_code FROM the_full_order.tb_account_meal_slot_setting WHERE account_id=? ORDER BY display_order", Integer.class, ACCOUNT);
	}

	void select(List<Integer> codes) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < codes.size(); i++) rows.add(Map.of("meal_slot_code", codes.get(i), "display_order", i));
		accounts.MealSlotSave(new HashMap<>(Map.of("account_id", ACCOUNT, "meal_slots", rows, "user_id", "TEST")));
		sql.clearCache();
	}

	@SuppressWarnings("unchecked")
	List<Map<String, Object>> profiles() {
		return (List<Map<String, Object>>) accounts.MealConfiguration(new HashMap<>(Map.of("account_id", ACCOUNT))).get("profiles");
	}

	@Test void addedMainMealSplitsTheDailyBudgetAndSnackDoesNot() {
		List<Integer> before = selected();
		assumeMains(before);
		BigDecimal elderly = jdbc.queryForObject("SELECT elderly FROM the_full.tb_account_info WHERE account_id=?", BigDecimal.class, ACCOUNT);

		int main = ((Number) slots.create(new HashMap<>(Map.of("meal_slot_name", "테스트새벽식", "slot_kind", "MAIN"))).get("meal_slot_code")).intValue();
		int snack = ((Number) slots.create(new HashMap<>(Map.of("meal_slot_name", "테스트아침간식", "slot_kind", "SNACK"))).get("meal_slot_code")).intValue();
		assertThat(main).isGreaterThanOrEqualTo(9);
		assertThat(snack).isEqualTo(main + 1);
		assertThatThrownBy(() -> slots.create(new HashMap<>(Map.of("meal_slot_name", "테스트새벽식", "slot_kind", "SNACK"))))
				.hasMessageContaining("같은 이름");

		List<Integer> next = new ArrayList<>(before);
		next.add(main); next.add(snack);
		select(next);
		var rows = profiles();
		var mainRow = rows.stream().filter(r -> ((Number) r.get("meal_slot_code")).intValue() == main).findFirst().orElseThrow();
		var snackRow = rows.stream().filter(r -> ((Number) r.get("meal_slot_code")).intValue() == snack).findFirst().orElseThrow();
		long mains = rows.stream().filter(r -> "MAIN".equals(r.get("slot_kind"))).count();
		assertThat(mainRow.get("slot_kind")).isEqualTo("MAIN");
		assertThat(((Number) mainRow.get("main_meal_count")).longValue()).isEqualTo(mains);
		assertThat(new BigDecimal(mainRow.get("budget_per_person").toString()))
				.isEqualByComparingTo(elderly.divide(BigDecimal.valueOf(mains), 2, java.math.RoundingMode.HALF_UP));
		assertThat(snackRow.get("budget_source")).isEqualTo("SNACK");
		assertThat(((Number) snackRow.get("default_food_type")).intValue()).as("간식 끼니 기본 분류 = 간식(6)").isEqualTo(6);
		// 금액대 템플릿의 1일 식단가 = 본식 단가 합(새 본식 포함, 간식 제외)
		var daily = new BigDecimal(templates.forAccount(new HashMap<>(Map.of("account_id", ACCOUNT))).get("daily_budget").toString());
		assertThat(daily).isEqualByComparingTo(new BigDecimal(mainRow.get("budget_per_person").toString()).multiply(BigDecimal.valueOf(mains)));

		// 사용 안 함: 이미 고른 거래처에는 보이고, 고르지 않은 거래처 목록에서는 빠진다
		slots.update(new HashMap<>(Map.of("meal_slot_code", snack, "meal_slot_name", "테스트아침간식", "slot_kind", "SNACK", "active_yn", "N")));
		sql.clearCache();
		assertThat(accounts.MealSlotList(new HashMap<>(Map.of("account_id", ACCOUNT)))).anyMatch(r -> ((Number) r.get("meal_slot_code")).intValue() == snack);
		assertThat(accounts.MealSlotList(new HashMap<>(Map.of("account_id", "NO-SUCH-ACCOUNT")))).noneMatch(r -> ((Number) r.get("meal_slot_code")).intValue() == snack);

		// 본사 순서: 새 끼니를 맨 앞으로
		slots.reorder(new HashMap<>(Map.of("meal_slot_codes", List.of(main))));
		sql.clearCache();
		assertThat(slots.list().get(0).get("meal_slot_code").toString()).isEqualTo(String.valueOf(main));
	}

	private static void assumeMains(List<Integer> before) {
		org.junit.jupiter.api.Assumptions.assumeTrue(before.contains(2), "테스트 거래처에 중식이 설정돼 있어야 함");
	}
}
