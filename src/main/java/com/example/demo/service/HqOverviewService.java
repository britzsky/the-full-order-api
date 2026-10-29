package com.example.demo.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 본사 홈 '오늘 할 일'(전체 거래처) 집계 — 2026-10-29 사용자 요청. 칸 순서: 식단표 제작 → 발주 필요 → 공급사 확인 → 입고 확인 → 위생 조치.
 * 공급사 확인·입고 확인은 /v2/workspace/tasks의 거래처별 숫자를 프런트가 합친다. 여기서는 나머지 세 가지만 업장 목록으로 돌려준다.
 * - 식단표 제작 필요: 종 알림(MealPlanAlertService)과 같이 목~일에만, 다음 주(월~일) 식단이 하나도 없는 업장(월~수는 빈 목록).
 *   대상은 관리 거래처 전체(화면 위 거래처 선택 목록 = the_full.tb_account del_yn N, 2026-10-29 사용자 결정). 금~일은 목요일 마감이 지나 '늦음'.
 * - 발주 필요: 저장된 식단(오늘~14일) 기준 부족 식재료가 있는 업장. 종 알림·상단 배너의 '식단 재료 N품목'과 같은 계산(ShortageProcurementService PLAN).
 * - 위생 조치 필요: 부적합은 있는데 조치가 끝나지 않은 기록이 있는 업장. 판정은 ERP 위생관리 화면과 같게(HYGIENE_OPEN):
 *   카테고리 있는 기록은 부적합 사진이 있고 조치 사진·조치 내용 중 하나라도 없으면, 카테고리 없는 예전 기록은 부적합 사진·내용이 있고 조치 내용이 없으면.
 * account가 있으면 그 거래처 한 곳만(본사가 거래처를 고른 홈).
 */
@Service
public class HqOverviewService {
    private static final Logger log = LoggerFactory.getLogger(HqOverviewService.class);
    private final JdbcTemplate jdbc;
    private final ShortageProcurementService shortages;

    public HqOverviewService(JdbcTemplate jdbc, ShortageProcurementService shortages) {
        this.jdbc = jdbc;
        this.shortages = shortages;
    }

    public Map<String, Object> overview(String account) { return overview(account, Clock.system(DeadlineAlertService.KST)); }

    Map<String, Object> overview(String account, Clock clock) {
        LocalDateTime now = clock.instant().atZone(DeadlineAlertService.KST).toLocalDateTime();
        LocalDate today = now.toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        boolean due = MealPlanAlertService.nextWeekStart(today) != null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked_at", now.toString());
        result.put("week_start", monday.toString());
        result.put("week_end", monday.plusDays(6).toString());
        result.put("meal_plan_due", due);
        result.put("meal_plan_late", due && today.getDayOfWeek() != MealPlanAlertService.DUE_DAY);
        result.put("meal_plan_needed", due ? mealPlanNeeded(account, monday) : List.of());
        result.put("order_needed", orderNeeded(account, today));
        result.put("hygiene_open", hygieneOpen(account));
        return result;
    }

    private List<Map<String, Object>> mealPlanNeeded(String account, LocalDate monday) {
        List<Object> args = new ArrayList<>(List.of(monday, monday.plusDays(6)));
        if (!account.isBlank()) args.add(account);
        return jdbc.queryForList("""
            SELECT a.account_id, a.account_name FROM the_full.tb_account a
            WHERE a.del_yn='N'
              AND NOT EXISTS (SELECT 1 FROM the_full_order.tb_account_meal_service m WHERE m.account_id=a.account_id
                     AND m.meal_date BETWEEN ? AND ? AND m.status<>'CANCELED')
            """ + scope(account) + " ORDER BY a.account_name LIMIT 500", args.toArray());
    }

    /** 조치 필요 기록(h) — 위생관리 화면 hygieneStatus·hygieneHas와 같은 규칙 */
    static final String HYGIENE_OPEN = """
        (CASE WHEN COALESCE(h.category_key,'')<>''
              THEN COALESCE(h.problem_image,'')<>'' AND NOT (COALESCE(h.clean_image,'')<>'' AND TRIM(COALESCE(h.clean_note,''))<>'')
              ELSE (COALESCE(h.problem_image,'')<>'' OR TRIM(COALESCE(h.problem_note,''))<>'') AND TRIM(COALESCE(h.clean_note,''))='' END)
        """;

    private static String scope(String account) { return account.isBlank() ? "" : " AND a.account_id=?"; }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> orderNeeded(String account, LocalDate today) {
        LocalDate to = today.plusDays(DeadlineAlertService.SEARCH_DAYS);
        // 계산할 식사가 있는 업장만 부족량을 계산한다(식사가 없으면 식단 기준 부족도 없다)
        var accounts = jdbc.queryForList("""
            SELECT a.account_id, a.account_name FROM the_full.tb_account a
            WHERE a.del_yn='N' AND EXISTS (
              SELECT 1 FROM the_full_order.tb_account_meal_service m
              JOIN the_full_order.tb_account_table_meals t ON t.table_id=m.table_id AND t.account_id=m.account_id
              WHERE m.account_id=a.account_id AND m.meal_date BETWEEN ? AND ?
                AND m.status NOT IN ('CANCELED','COMPLETED') AND t.status<>'CANCELED')
            """ + scope(account) + " ORDER BY a.account_name LIMIT 500",
            account.isBlank() ? new Object[]{today, to} : new Object[]{today, to, account});
        List<Map<String, Object>> result = new ArrayList<>();
        for (var target : accounts) {
            Map<String, Object> criteria = new LinkedHashMap<>();
            criteria.put("account_id", target.get("account_id"));
            criteria.put("source", "PLAN");
            criteria.put("date_from", today.toString());
            criteria.put("date_to", to.toString());
            criteria.put("delivery_date", today.toString());
            criteria.put("skip_offers", true);
            int items = 0;
            try {
                for (var row : (List<Map<String, Object>>) shortages.shortages(criteria).get("ingredients"))
                    if (new BigDecimal(Objects.toString(row.get("shortage_qty"), "0")).signum() > 0) items++;
            } catch (RuntimeException e) {
                // 레시피 없는 메뉴 등으로 계산이 안 되는 업장은 확인이 필요하다는 뜻으로 남긴다
                log.warn("본사 홈 발주 필요 계산 실패 account={}: {}", target.get("account_id"), e.getMessage());
                Map<String, Object> row = new LinkedHashMap<>(target);
                row.put("items", null);
                row.put("error", e.getMessage());
                result.add(row);
                continue;
            }
            if (items == 0) continue;
            Map<String, Object> row = new LinkedHashMap<>(target);
            row.put("items", items);
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> hygieneOpen(String account) {
        return jdbc.queryForList("""
            SELECT a.account_id, a.account_name, COUNT(*) open_count
            FROM the_full.tb_account_hygiene_management h
            JOIN the_full.tb_account a ON a.account_id=h.account_id AND a.del_yn='N'
            WHERE COALESCE(h.del_yn,'N')='N'
              AND """ + HYGIENE_OPEN + """
            """ + scope(account) + " GROUP BY a.account_id, a.account_name ORDER BY open_count DESC, a.account_name LIMIT 500",
            account.isBlank() ? new Object[0] : new Object[]{account});
    }
}
