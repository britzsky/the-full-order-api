package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 로컬 DB(조회만): 본사 홈 '오늘 할 일' 집계(식단표 제작·발주 필요·위생 조치)가 실제 스키마에서 돌고,
 * 위생 조치 필요 업장 수가 '조치 사진·내용 모두 없음' 기록이 있는 업장 수와 같다.
 * 실행: RUN_LOCAL_DB_TESTS=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LocalHqOverviewDatabaseTest {
	@Autowired JdbcTemplate jdbc;
	@Autowired HqOverviewService overview;

	@Test
	@SuppressWarnings("unchecked")
	void summarizesAccountsForHeadquartersHome() {
		Map<String, Object> result = overview.overview("");
		var meal = (List<Map<String, Object>>) result.get("meal_plan_needed");
		var order = (List<Map<String, Object>>) result.get("order_needed");
		var hygiene = (List<Map<String, Object>>) result.get("hygiene_open");
		Integer expected = jdbc.queryForObject("""
				SELECT COUNT(DISTINCT h.account_id) FROM the_full.tb_account_hygiene_management h
				JOIN the_full.tb_account a ON a.account_id=h.account_id AND a.del_yn='N'
				WHERE COALESCE(h.del_yn,'N')='N' AND """ + HqOverviewService.HYGIENE_OPEN, Integer.class);
		assertEquals(expected, hygiene.size());
		assertTrue(order.stream().allMatch(row -> row.get("items") == null || ((Number) row.get("items")).intValue() > 0));
		// 관리 거래처 전체가 대상: 다음 주 식단이 없는 업장 수와 같다
		Integer noPlan = jdbc.queryForObject("""
				SELECT COUNT(*) FROM the_full.tb_account a WHERE a.del_yn='N' AND NOT EXISTS(SELECT 1 FROM the_full_order.tb_account_meal_service m
				WHERE m.account_id=a.account_id AND m.meal_date BETWEEN ? AND ? AND m.status<>'CANCELED')""", Integer.class, result.get("week_start"), result.get("week_end"));
		assertEquals(Boolean.TRUE.equals(result.get("meal_plan_due")) ? noPlan : 0, meal.size());
		if (!hygiene.isEmpty()) {
			String one = hygiene.get(0).get("account_id").toString();
			var scoped = (List<Map<String, Object>>) overview.overview(one).get("hygiene_open");
			assertEquals(1, scoped.size());
			assertEquals(one, scoped.get(0).get("account_id"));
		}
		// 월~수에는 식단표 제작 필요를 세지 않는다(종 알림과 같은 목요일 기준)
		var monday = java.time.Clock.fixed(java.time.LocalDate.of(2026, 10, 26).atTime(9, 0).atZone(DeadlineAlertService.KST).toInstant(), DeadlineAlertService.KST);
		assertTrue(((List<?>) overview.overview("", monday).get("meal_plan_needed")).isEmpty());
		var thursday = java.time.Clock.fixed(java.time.LocalDate.of(2026, 10, 29).atTime(9, 0).atZone(DeadlineAlertService.KST).toInstant(), DeadlineAlertService.KST);
		assertEquals(Boolean.FALSE, overview.overview("", thursday).get("meal_plan_late"));
		System.out.println("[본사 홈] 다음 주 " + result.get("week_start") + " 식단표 제작 필요 " + meal.size() + "곳, 발주 필요 " + order.size()
				+ "곳, 위생 조치 필요 " + hygiene.size() + "곳 " + hygiene.stream().limit(3).map(r -> r.get("account_name") + "(" + r.get("open_count") + ")").toList());
	}
}
