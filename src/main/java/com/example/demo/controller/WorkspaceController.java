package com.example.demo.controller;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import com.example.demo.security.WorkspaceAccess;
import com.example.demo.service.MealPlanAlertService;

@RestController
@RequestMapping("/v2/workspace")
public class WorkspaceController {
    private final JdbcTemplate jdbc;
    private final WorkspaceAccess access;
    private final MealPlanAlertService mealPlanAlerts;
    public WorkspaceController(JdbcTemplate jdbc,WorkspaceAccess access,MealPlanAlertService mealPlanAlerts) { this.jdbc=jdbc;this.access=access;this.mealPlanAlerts=mealPlanAlerts; }
    /** 머리글 종 알림(다음 주 식단표 미작성). 발주 마감은 프런트가 /Procurement/DeadlineAlerts 결과를 함께 보여준다. 본사가 거래처를 고르지 않으면 전체 거래처. */
    @GetMapping("/alerts")
    public Map<String,Object> alerts(@RequestParam(name="account_id",defaultValue="") String account_id,HttpServletRequest request) {
        var user=WorkspaceAccess.user(request);
        String account=user.headquarters()?account_id:user.accountId();
        access.checkAccount(user,account);
        return Map.of("alerts",mealPlanAlerts.alerts(account),"checked_at",java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")).toString());
    }
    @GetMapping("/tasks")
    public Map<String,Object> tasks(@RequestParam(name="account_id",defaultValue="") String account_id,HttpServletRequest request) {
        var user=WorkspaceAccess.user(request);
        String account=user.headquarters()?account_id:user.accountId();
        access.checkAccount(user,account);
        String scope=account.isBlank()?"":" AND a.account_id=?";
        Object[] args=account.isBlank()?new Object[0]:new Object[]{account};
        var rows=jdbc.queryForList("""
            SELECT a.account_id,a.account_name,
              (SELECT COUNT(*) FROM the_full_order.tb_account_purchase_order p WHERE p.account_id=a.account_id
                AND p.status IN ('SENDING','UNKNOWN','PARTIAL_FAILED')) pending_orders,
              (SELECT COUNT(*) FROM the_full_order.tb_account_purchase_order p WHERE p.account_id=a.account_id
                AND p.requested_delivery_date<=CURRENT_DATE AND p.status IN ('ORDERED','CONFIRMED','PARTIAL_RECEIVED','NOT_RECEIVED')) due_receipts,
              (SELECT COUNT(*) FROM the_full_order.tb_account_meal_service m WHERE m.account_id=a.account_id
                AND m.meal_date=CURRENT_DATE AND m.status='DRAFT') pending_meals,
              (SELECT COUNT(*) FROM the_full_order.tb_supplier_account_site s WHERE s.account_id=a.account_id AND s.active_yn='Y') mapped_suppliers,
              (SELECT COUNT(*) FROM the_full_order.tb_workspace_notification n WHERE n.account_id=a.account_id
                AND n.event_type='SUPPLIER_STATUS' AND n.status<>'RESOLVED') supplier_notices
            FROM the_full.tb_account a WHERE a.del_yn='N'
            """+scope+" ORDER BY pending_orders DESC,due_receipts DESC,a.account_name LIMIT 500",args);
        return Map.of("role",user.headquarters()?"HEADQUARTERS":"SITE","accounts",rows,"checked_at",java.time.LocalDateTime.now().toString());
    }
    /**
     * 홈 오른쪽 거래처 카드: 거래처명·로그인 사용자 이름·담당자(the_full.tb_account_info)·이달 예산/사용/여유.
     * 예산 = 기존 ERP 예산관리(the_full.tb_account_budget_management)의 이번 달 실 예산 부여 합계(없으면 예산부여). 읽기만 한다.
     * 사용 = 이번 달 납품일의 접수·입고 품목 금액(실패·취소·미입고·접수 불명확 제외) + 이번 달 직접구매 금액.
     */
    @GetMapping("/account-summary")
    public Map<String,Object> accountSummary(@RequestParam(name="account_id",defaultValue="") String account_id,HttpServletRequest request) {
        var user=WorkspaceAccess.user(request);
        String account=user.headquarters()?account_id:user.accountId();
        if(account.isBlank()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"거래처를 선택해주세요.");
        access.checkAccount(user,account);
        var month=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul"));
        var from=month.atDay(1);var to=month.atEndOfMonth();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("account_id",account);
        result.put("month",month.toString());
        result.put("account_name",first("SELECT account_name FROM the_full.tb_account WHERE account_id=?",account));
        result.put("user_name",first("SELECT user_name FROM the_full.tb_user WHERE user_id=?",user.userId()));
        var info=jdbc.queryForList("SELECT manager_name,manager_tel,manager_name2,manager_tel2 FROM the_full.tb_account_info WHERE account_id=? LIMIT 1",account);
        result.put("manager_name",info.isEmpty()?null:info.get(0).get("manager_name"));
        result.put("manager_tel",info.isEmpty()?null:info.get(0).get("manager_tel"));
        var budget=jdbc.queryForList("SELECT budget_total,budget_grant FROM the_full.tb_account_budget_management WHERE account_id=? AND year=? AND month=? AND day IS NULL LIMIT 1",account,month.getYear(),month.getMonthValue());
        long total=budget.isEmpty()?0:number(budget.get(0).get("budget_total")), grant=budget.isEmpty()?0:number(budget.get(0).get("budget_grant"));
        Long amount=total>0?Long.valueOf(total):grant>0?Long.valueOf(grant):null;
        result.put("budget",amount);
        result.put("budget_source",total>0?"실 예산 부여 합계":grant>0?"예산부여":"미등록");
        long orders=number(first("""
            SELECT COALESCE(SUM(i.line_amount),0) FROM the_full_order.tb_account_purchase_order po
            JOIN the_full_order.tb_account_purchase_order_item i ON i.purchase_order_id=po.purchase_order_id
            WHERE po.account_id=? AND po.requested_delivery_date BETWEEN ? AND ?
              AND i.item_status IN ('ORDERED','CONFIRMED','PARTIAL_RECEIVED','RECEIVED')
            """,account,from,to));
        long direct=number(first("""
            SELECT COALESCE(SUM(amount_delta),0) FROM the_full_order.tb_account_inventory_movement
            WHERE account_id=? AND reference_type='DIRECT_PURCHASE' AND movement_at>=? AND movement_at<?
            """,account,from,to.plusDays(1)));
        result.put("used_orders",orders);result.put("used_direct",direct);result.put("used",orders+direct);
        result.put("remaining",amount==null?null:amount-orders-direct);
        return result;
    }
    private Object first(String sql,Object... args) {
        var rows=jdbc.queryForList(sql,args);
        return rows.isEmpty()?null:rows.get(0).values().iterator().next();
    }
    private static long number(Object value) {
        if(value==null) return 0;
        try { return Math.round(Double.parseDouble(value.toString())); } catch(NumberFormatException e) { return 0; }
    }
    /** 홈 달력: 한 달의 날짜별 발주서(납품일 기준 입고 완료/진행 중)와 식단 끼니 수. 거래처 한 곳만. */
    @GetMapping("/calendar")
    public Map<String,Object> calendar(@RequestParam(name="account_id",defaultValue="") String account_id,
            @RequestParam(name="month") String month,HttpServletRequest request) {
        var user=WorkspaceAccess.user(request);
        String account=user.headquarters()?account_id:user.accountId();
        if(account.isBlank()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"거래처를 선택해주세요.");
        access.checkAccount(user,account);
        java.time.LocalDate from;
        try { from=java.time.YearMonth.parse(month).atDay(1); }
        catch(RuntimeException e) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"월은 YYYY-MM 형식입니다."); }
        var to=from.plusMonths(1).minusDays(1);
        Map<String,Map<String,Object>> days=new TreeMap<>();
        for(var row:jdbc.queryForList("""
            SELECT DATE_FORMAT(requested_delivery_date,'%Y-%m-%d') d,
              SUM(status='RECEIVED') received,
              SUM(status IN ('SENDING','UNKNOWN','PARTIAL_FAILED','ORDERED','CONFIRMED','PARTIAL_RECEIVED','NOT_RECEIVED')) open_orders
            FROM the_full_order.tb_account_purchase_order WHERE account_id=? AND requested_delivery_date BETWEEN ? AND ?
            GROUP BY requested_delivery_date
            """,account,from,to)) {
            var day=days.computeIfAbsent(row.get("d").toString(),k->new LinkedHashMap<>());
            day.put("received",row.get("received"));day.put("open_orders",row.get("open_orders"));
        }
        for(var row:jdbc.queryForList("""
            SELECT DATE_FORMAT(meal_date,'%Y-%m-%d') d,COUNT(*) meals FROM the_full_order.tb_account_meal_service
            WHERE account_id=? AND meal_date BETWEEN ? AND ? AND status<>'CANCELED' GROUP BY meal_date
            """,account,from,to))
            days.computeIfAbsent(row.get("d").toString(),k->new LinkedHashMap<>()).put("meals",row.get("meals"));
        List<Map<String,Object>> result=new ArrayList<>();
        days.forEach((date,values)->{ var item=new LinkedHashMap<String,Object>();item.put("date",date);
            item.put("received",values.getOrDefault("received",0));item.put("open_orders",values.getOrDefault("open_orders",0));item.put("meals",values.getOrDefault("meals",0));result.add(item); });
        return Map.of("month",month,"days",result);
    }
}
