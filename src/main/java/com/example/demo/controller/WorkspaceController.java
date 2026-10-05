package com.example.demo.controller;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import com.example.demo.security.WorkspaceAccess;

@RestController
@RequestMapping("/v2/workspace")
public class WorkspaceController {
    private final JdbcTemplate jdbc;
    private final WorkspaceAccess access;
    public WorkspaceController(JdbcTemplate jdbc,WorkspaceAccess access) { this.jdbc=jdbc;this.access=access; }
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
              (SELECT COUNT(*) FROM the_full_order.tb_supplier_account_site s WHERE s.account_id=a.account_id AND s.active_yn='Y') mapped_suppliers
            FROM the_full.tb_account a WHERE a.del_yn='N'
            """+scope+" ORDER BY pending_orders DESC,due_receipts DESC,a.account_name LIMIT 500",args);
        return Map.of("role",user.headquarters()?"HEADQUARTERS":"SITE","accounts",rows,"checked_at",java.time.LocalDateTime.now().toString());
    }
}
