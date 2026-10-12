package com.example.demo.service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 다음 주 식단표 미작성 알림(머리글 종 알림). 다음 주 식단은 그 주 발주의 근거라 목요일까지 만들어야 한다.
 * 목요일(당일, '오늘까지')부터 일요일('늦음')까지, 다음 주 월~일에 취소 아닌 식사가 하나도 없으면 알린다.
 * 최근 4주 안에 식단을 쓴 적이 없는 거래처는 식단표를 쓰지 않는 곳으로 보고 알리지 않는다(매주 같은 알림 방지).
 */
@Service
public class MealPlanAlertService {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 다음 주 식단 작성 마감 요일 */
    static final DayOfWeek DUE_DAY = DayOfWeek.THURSDAY;
    static final int RECENT_DAYS = 28;
    private final JdbcTemplate jdbc;

    public MealPlanAlertService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> alerts(String account) { return alerts(account, Clock.system(KST)); }

    /** 알림 기간이면 다음 주 월요일, 아니면(월~수) null */
    static LocalDate nextWeekStart(LocalDate today) {
        if (today.getDayOfWeek().getValue() < DUE_DAY.getValue()) return null;
        return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    /** 시각을 지정한 조회(테스트·다른 패키지의 읽기 전용 점검용) */
    public List<Map<String, Object>> alertsAt(String account, Clock clock) { return alerts(account, clock); }

    List<Map<String, Object>> alerts(String account, Clock clock) {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        LocalDate monday = nextWeekStart(today);
        if (monday == null) return List.of();
        LocalDate sunday = monday.plusDays(6);
        boolean late = today.getDayOfWeek() != DUE_DAY;
        String scope = account.isBlank() ? "" : " AND a.account_id=?";
        List<Object> args = new ArrayList<>(List.of(today.minusDays(RECENT_DAYS), monday.minusDays(1), monday, sunday));
        if (!account.isBlank()) args.add(account);
        var rows = jdbc.queryForList("""
            SELECT a.account_id, a.account_name FROM the_full.tb_account a
            WHERE a.del_yn='N'
              AND EXISTS (SELECT 1 FROM the_full_order.tb_account_meal_service m WHERE m.account_id=a.account_id
                AND m.meal_date BETWEEN ? AND ? AND m.status<>'CANCELED')
              AND NOT EXISTS (SELECT 1 FROM the_full_order.tb_account_meal_service m WHERE m.account_id=a.account_id
                AND m.meal_date BETWEEN ? AND ? AND m.status<>'CANCELED')
            """ + scope + " ORDER BY a.account_name LIMIT 500", args.toArray());
        String period = monday.getMonthValue() + "/" + monday.getDayOfMonth() + "~" + sunday.getMonthValue() + "/" + sunday.getDayOfMonth();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("type", "MEAL_NEXT_WEEK");
            alert.put("key", "MEAL_NEXT_WEEK:" + row.get("account_id") + ":" + monday);
            alert.put("level", late ? "urgent" : "warn");
            alert.put("account_id", row.get("account_id"));
            alert.put("account_name", row.get("account_name"));
            alert.put("title", "다음 주(" + period + ") 식단표 미작성");
            alert.put("detail", late ? "목요일 마감이 지났습니다. 식단을 만들어야 발주량이 계산됩니다." : "오늘(목)까지 만들어야 다음 주 발주를 준비할 수 있습니다.");
            alert.put("week_start", monday.toString());
            alert.put("path", "/operations/table-meals");
            result.add(alert);
        }
        return result;
    }
}
