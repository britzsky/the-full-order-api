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
