package com.example.demo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceNotificationService {
    private final JdbcTemplate jdbc;
    private final ShortageProcurementService shortages;
    public WorkspaceNotificationService(JdbcTemplate jdbc,ShortageProcurementService shortages) { this.jdbc=jdbc;this.shortages=shortages; }
    public List<Map<String,Object>> list(String account) {
        return jdbc.queryForList("SELECT * FROM the_full_order.tb_workspace_notification WHERE account_id=? AND status<>'RESOLVED' ORDER BY updated_at DESC LIMIT 100",account);
    }
    @Transactional
    public List<Map<String,Object>> refresh(String account) {
        if(account.isBlank()) throw new IllegalArgumentException("거래처를 선택해주세요.");
        // Serialize refresh and state changes for the same account; no lost acknowledge updates.
        lock(account);
        Set<String> active=new HashSet<>();
        for(var order:jdbc.queryForList("""
            SELECT purchase_order_id,status,requested_delivery_date FROM the_full_order.tb_account_purchase_order
            WHERE account_id=? AND (status IN ('SENDING','UNKNOWN','PARTIAL_FAILED')
              OR (requested_delivery_date<=CURRENT_DATE AND status IN ('ORDERED','CONFIRMED','PARTIAL_RECEIVED','NOT_RECEIVED')))
            """,account)) {
            String status=order.get("status").toString(),id=order.get("purchase_order_id").toString();
            boolean unknown=Set.of("SENDING","UNKNOWN","PARTIAL_FAILED").contains(status);
            String key="ORDER:"+id;active.add(key);
            put(account,key,"TASK",(unknown?"주문 접수 확인 필요 · ":"입고 확인 필요 · ")+order.get("requested_delivery_date"),"/order_manager/orders",status+"|"+order.get("requested_delivery_date"));
        }
        for(var meal:jdbc.queryForList("SELECT meal_service_id,meal_slot,meal_date FROM the_full_order.tb_account_meal_service WHERE account_id=? AND meal_date=CURRENT_DATE AND status='DRAFT'",account)) {
            String key="MEAL:"+meal.get("meal_service_id");active.add(key);
            put(account,key,"TASK","오늘 실제 사용 확인 · "+meal.get("meal_slot"),"/operations/table-meals",meal.get("meal_date").toString());
        }
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        var criteria=new HashMap<String,Object>();criteria.put("account_id",account);criteria.put("source","PLAN");criteria.put("date_from",today.toString());criteria.put("date_to",today.plusDays(7).toString());criteria.put("delivery_date",today.toString());
        var result=shortages.shortages(criteria);
        if(result.get("ingredients") instanceof List<?> ingredients) for(Object value:ingredients) {
            var row=(Map<?,?>)value;String key="SHORTAGE:"+hash(Objects.toString(row.get("ingredient_id"))+"|"+row.get("needed_by"));active.add(key);
            String title=Objects.toString(row.get("ingredient_name"))+" · "+row.get("needed_by")+" 부족·확인 필요 "+row.get("shortage_qty")+" "+row.get("base_unit");
            put(account,key,"TASK",title,"/order_manager/recommended",row.get("shortage_qty")+"|"+row.get("issues"));
        }
        for(var row:jdbc.queryForList("SELECT event_key FROM the_full_order.tb_workspace_notification WHERE account_id=? AND event_type='TASK' AND status<>'RESOLVED'",account))
            if(!active.contains(row.get("event_key"))) jdbc.update("UPDATE the_full_order.tb_workspace_notification SET status='RESOLVED',actor_id='SYSTEM',updated_at=NOW() WHERE account_id=? AND event_key=?",account,row.get("event_key"));
        return list(account);
    }
    private void lock(String account) {
        if(jdbc.queryForList("SELECT account_id FROM the_full.tb_account WHERE account_id=? AND del_yn='N' FOR UPDATE",account).isEmpty())
            throw new IllegalArgumentException("거래처를 찾을 수 없습니다.");
    }
    private void put(String account,String key,String type,String title,String path,String facts) {
        jdbc.update("""
            INSERT INTO the_full_order.tb_workspace_notification(account_id,event_key,event_type,fingerprint,title,action_path)
            VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE
              updated_at=IF(fingerprint<>VALUES(fingerprint) OR status='RESOLVED',NOW(),updated_at),
              actor_id=IF(fingerprint<>VALUES(fingerprint) OR status='RESOLVED',NULL,actor_id),
              status=IF(fingerprint<>VALUES(fingerprint) OR status='RESOLVED','OPEN',status),
              fingerprint=VALUES(fingerprint),title=VALUES(title),action_path=VALUES(action_path)
            """,account,key,type,hash(title+"|"+facts),title,path);
    }
    @Transactional
    public void state(String account,String key,String fingerprint,String state,String actor) {
        if(!Set.of("IN_PROGRESS","OPEN","RESOLVED").contains(state)) throw new IllegalArgumentException("알림 처리 상태를 확인해주세요.");
        lock(account);
        if(jdbc.update("UPDATE the_full_order.tb_workspace_notification SET status=?,actor_id=?,updated_at=NOW() WHERE account_id=? AND event_key=? AND fingerprint=? AND (event_type<>'TASK' OR ?<>'RESOLVED')",state,actor,account,key,fingerprint,state)==0)
            throw new IllegalArgumentException("알림이 변경되었거나 아직 업무가 해결되지 않았습니다. 다시 조회해주세요.");
    }
    @Transactional
    public void mealChanged(String account,String table,String actor) {
        lock(account);
        put(account,"PLAN:"+hash(table),"MEAL_CHANGE","식단이 저장·변경되었습니다. 기존 발주와 부족량을 확인해주세요.","/operations/table-meals",UUID.randomUUID()+"|"+actor);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
