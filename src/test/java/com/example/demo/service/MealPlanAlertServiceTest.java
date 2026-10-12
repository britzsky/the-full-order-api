package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 다음 주 식단표 미작성 알림: 목요일부터, 다음 주 월~일 범위, 목요일 '오늘까지' / 이후 '늦음' */
class MealPlanAlertServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final MealPlanAlertService service = new MealPlanAlertService(jdbc);
    static Clock at(String kstDate) { return Clock.fixed(LocalDate.parse(kstDate).atTime(9, 0).atZone(MealPlanAlertService.KST).toInstant(), ZoneOffset.UTC); }

    @Test void noAlertBeforeThursdayAndNoQuery() {
        assertThat(service.alerts("A", at("2026-10-07"))).isEmpty();   // 수요일
        verifyNoInteractions(jdbc);
    }

    @Test void thursdayChecksNextMondayToSundayAsDueToday() {
        List<Object> sent = new ArrayList<>();
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            sent.add(inv.getRawArguments()[0]); sent.addAll(Arrays.asList((Object[]) inv.getRawArguments()[1]));
            return List.of(Map.of("account_id", "A", "account_name", "우리누리"));
        });
        var alerts = service.alerts("A", at("2026-10-08"));            // 목요일
        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.get("level")).isEqualTo("warn");
            assertThat(a.get("title").toString()).contains("10/12~10/18");
            assertThat(a.get("week_start")).isEqualTo("2026-10-12");
        });
        assertThat(sent.get(0).toString()).contains("AND a.account_id=?");
        assertThat(sent.subList(1, sent.size())).containsExactly(LocalDate.parse("2026-09-10"), LocalDate.parse("2026-10-11"), LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "A");
    }

    @Test void afterThursdayIsLateAndHeadquartersChecksAllAccounts() {
        List<String> sql = new ArrayList<>();
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            sql.add(inv.getRawArguments()[0].toString());
            return List.of(Map.of("account_id", "A", "account_name", "우리누리"), Map.of("account_id", "B", "account_name", "한빛"));
        });
        var alerts = service.alerts("", at("2026-10-11"));             // 일요일
        assertThat(alerts).hasSize(2).allSatisfy(a -> assertThat(a.get("level")).isEqualTo("urgent"));
        assertThat(sql).singleElement().satisfies(s -> assertThat(s).doesNotContain("AND a.account_id=?"));
    }

    @Test void nextWeekStartIsFollowingMonday() {
        assertThat(MealPlanAlertService.nextWeekStart(LocalDate.parse("2026-10-05"))).isNull();          // 월
        assertThat(MealPlanAlertService.nextWeekStart(LocalDate.parse("2026-10-09"))).isEqualTo("2026-10-12"); // 금
        assertThat(MealPlanAlertService.nextWeekStart(LocalDate.parse("2026-10-11"))).isEqualTo("2026-10-12"); // 일
    }
}
