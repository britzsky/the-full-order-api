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
            put(account,key,"TASK",(unknown?"주문 접수 확인 요망 · ":"입고 확인 요망 · ")+order.get("requested_delivery_date"),"/order_manager/orders",status+"|"+order.get("requested_delivery_date"));
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
        // 공급사 출고·주문 상태 알림은 해당 발주가 입고·미입고·취소로 끝나면 닫는다.
        jdbc.update("""
            UPDATE the_full_order.tb_workspace_notification n
            LEFT JOIN the_full_order.tb_account_purchase_order po ON po.account_id=n.account_id AND po.purchase_order_id=SUBSTRING(n.event_key,10)
            SET n.status='RESOLVED',n.actor_id='SYSTEM',n.updated_at=NOW()
            WHERE n.account_id=? AND n.event_type='SUPPLIER_STATUS' AND n.status<>'RESOLVED'
              AND (po.purchase_order_id IS NULL OR po.status NOT IN ('ORDERED','CONFIRMED','PARTIAL_RECEIVED'))
            """,account);
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
    /**
     * 공급사 쪽 발주 상태(출고완료·수량 변경·삭제 등). 배치가 매시간 다시 써도 내용이 같으면
     * 사용자가 닫은 상태를 다시 열지 않는다 — 내용이 바뀌었을 때만 다시 연다.
     */
    @Transactional
    public String supplierStatus(String account,String orderId,String title,String facts) {
        lock(account);
        String fingerprint=hash(title+"|"+facts);
        jdbc.update("""
            INSERT INTO the_full_order.tb_workspace_notification(account_id,event_key,event_type,fingerprint,title,action_path)
            VALUES(?,?,'SUPPLIER_STATUS',?,?,'/order_manager/orders') ON DUPLICATE KEY UPDATE
              updated_at=IF(fingerprint<>VALUES(fingerprint),NOW(),updated_at),
              actor_id=IF(fingerprint<>VALUES(fingerprint),NULL,actor_id),
              status=IF(fingerprint<>VALUES(fingerprint),'OPEN',status),
              fingerprint=VALUES(fingerprint),title=VALUES(title)
            """,account,"SUPPLIER:"+orderId,fingerprint,title);
        return fingerprint;
    }
    /** 더 확인할 것이 없어진 공급사 상태 알림(예: 반영 지연으로 안 보이던 주문이 조회됨)을 닫는다. */
    @Transactional
    public void resolveSupplierStatus(String account,String orderId) {
        jdbc.update("UPDATE the_full_order.tb_workspace_notification SET status='RESOLVED',actor_id='SYSTEM',updated_at=NOW() WHERE account_id=? AND event_key=? AND status<>'RESOLVED'",account,"SUPPLIER:"+orderId);
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
