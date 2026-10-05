package com.example.demo.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Date-scoped catalog preparation with a durable lease and bounded worker queue. */
@Service
public class CatalogPreparationService {
    private final JdbcTemplate jdbc;
    private final OurhomeCatalogSyncService ourhome;
    private final WelstoryCatalogSyncService welstory;
    private OurhomeSiteService sites;
    @org.springframework.beans.factory.annotation.Autowired
    public void setSites(OurhomeSiteService sites) { this.sites=sites; }
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16), r -> { var t=new Thread(r,"catalog-preparation");t.setDaemon(true);return t; });
    public CatalogPreparationService(JdbcTemplate jdbc, OurhomeCatalogSyncService ourhome, WelstoryCatalogSyncService welstory) {
        this.jdbc=jdbc;this.ourhome=ourhome;this.welstory=welstory;
    }
    private LocalDate date(String value) {
        var date=LocalDate.parse(value);var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        if(date.isBefore(today)||date.isAfter(today.plusDays(31))) throw new IllegalArgumentException("상품 준비일은 오늘부터 31일 이내로 선택해주세요.");
        return date;
    }
    public Map<String,Object> prepare(String account,String value,String actor) {
        LocalDate date=date(value);
        if(account==null||account.isBlank()) throw new IllegalArgumentException("거래처를 선택해주세요.");
        for(var site:jdbc.queryForList("""
            SELECT ss.*,s.supplier_code FROM the_full_order.tb_supplier_account_site ss
            JOIN the_full_order.tb_supplier s ON s.supplier_id=ss.supplier_id
            WHERE ss.account_id=? AND ss.active_yn='Y' AND s.supplier_code IN ('WELSTORY','OURHOME')
            """,account)) {
            String type="PREPARE_"+date; Object supplier=site.get("supplier_id"), id=site.get("supplier_account_site_id");
            jdbc.update("INSERT IGNORE INTO the_full_order.tb_supplier_sync_checkpoint(supplier_id,supplier_account_site_id,sync_type) VALUES(?,?,?)",supplier,id,type);
            int claimed=jdbc.update("""
                UPDATE the_full_order.tb_supplier_sync_checkpoint SET last_started_at=NOW(),last_result_code='PREPARING',last_result_message='납품일 상품 준비 중'
                WHERE supplier_id=? AND supplier_account_site_id=? AND sync_type=?
                  AND (last_started_at IS NULL OR last_result_code<>'PREPARING' OR last_started_at<DATE_SUB(NOW(),INTERVAL 30 MINUTE))
                  AND (last_succeeded_at IS NULL OR last_succeeded_at<DATE_SUB(NOW(),INTERVAL 12 HOUR))
                  AND (last_failed_at IS NULL OR last_failed_at<DATE_SUB(NOW(),INTERVAL 5 MINUTE))
                """,supplier,id,type);
            if(claimed==0) continue;
            try { executor.execute(()->run(site,date,actor,type)); }
            catch(RejectedExecutionException e) { failed(supplier,id,type,"다른 상품 준비가 진행 중입니다. 잠시 후 다시 시도해주세요."); }
        }
        return status(account,date.toString());
    }
    private void run(Map<String,Object> site,LocalDate date,String actor,String type) {
        Object id=site.get("supplier_account_site_id"), supplier=site.get("supplier_id");
        try {
            Map<String,Object> command=new HashMap<>();command.put("account_id",site.get("account_id"));command.put("user_id",actor);
            if("OURHOME".equals(site.get("supplier_code"))) {
                command.put("siteCode",site.get("external_site_code"));command.put("deliveryDate",date.toString());ourhome.sync(command);
            } else {
                var periods=jdbc.queryForList("""
                    SELECT DISTINCT provider_revision FROM the_full_order.tb_supplier_site_product_offer
                    WHERE supplier_account_site_id=? AND active_yn='Y' AND valid_from<=? AND valid_to>=?
                      AND provider_revision REGEXP '^[0-9]{4}-[0-9]{2}$'
                    """,id,date.toString(),date.toString());
                if(periods.size()!=1) throw new IllegalArgumentException("본사에서 해당 납품일의 웰스토리 유효 순기를 먼저 확인·등록해주세요.");
                String[] period=periods.get(0).get("provider_revision").toString().split("-");
                command.put("sold_to",site.get("external_site_code"));command.put("period_group_year",period[0]);command.put("period_group",period[1]);welstory.syncCatalog(command);
            }
            jdbc.update("""
                UPDATE the_full_order.tb_supplier_sync_checkpoint SET last_succeeded_at=NOW(),last_result_code='READY',last_result_message='납품일 상품 준비 완료',consecutive_failures=0
                WHERE supplier_id=? AND supplier_account_site_id=? AND sync_type=?
                """,supplier,id,type);
        } catch(RuntimeException e) { failed(supplier,id,type,e instanceof IllegalArgumentException ? e.getMessage() : "상품 준비에 실패했습니다. 본사 연동 설정과 공급사 응답을 확인해주세요."); }
    }
    private void failed(Object supplier,Object id,String type,String message) {
        jdbc.update("""
            UPDATE the_full_order.tb_supplier_sync_checkpoint SET last_failed_at=NOW(),last_result_code='REVIEW_REQUIRED',last_result_message=?,consecutive_failures=consecutive_failures+1
            WHERE supplier_id=? AND supplier_account_site_id=? AND sync_type=?
            """,message,supplier,id,type);
    }
    public Map<String,Object> status(String account,String value) {
        var date=date(value);
        var rows=jdbc.queryForList("""
            SELECT s.supplier_code,s.supplier_name,ss.external_site_name,c.last_succeeded_at,c.last_failed_at,
              COALESCE(c.last_result_code,'NOT_PREPARED') status,c.last_result_message message
            FROM the_full_order.tb_supplier_account_site ss JOIN the_full_order.tb_supplier s ON s.supplier_id=ss.supplier_id
            LEFT JOIN the_full_order.tb_supplier_sync_checkpoint c ON c.supplier_account_site_id=ss.supplier_account_site_id AND c.sync_type=?
            WHERE ss.account_id=? AND ss.active_yn='Y' AND s.supplier_code IN ('WELSTORY','OURHOME')
            ""","PREPARE_"+date,account);
        return Map.of("delivery_date",date.toString(),"suppliers",rows);
    }
    public void scheduled() {
        for(var target:jdbc.queryForList("""
            SELECT DISTINCT m.account_id,m.meal_date FROM the_full_order.tb_account_meal_service m
            WHERE m.status='DRAFT' AND m.meal_date BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE,INTERVAL 7 DAY)
            ORDER BY m.meal_date,m.account_id LIMIT 30
            """)) prepare(target.get("account_id").toString(),target.get("meal_date").toString(),"SYSTEM");
    }
    public void initial(String account,String actor) {
        try { executor.execute(()-> {
            try {
                var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
                var dates=new TreeSet<LocalDate>();
                for(var site:jdbc.queryForList("SELECT ss.external_site_code FROM the_full_order.tb_supplier_account_site ss JOIN the_full_order.tb_supplier s ON s.supplier_id=ss.supplier_id WHERE ss.account_id=? AND ss.active_yn='Y' AND s.supplier_code='OURHOME'",account))
                    sites.requireSite(site.get("external_site_code").toString()).deliveryDates().stream().map(LocalDate::parse)
                        .filter(d->!d.isBefore(today)&&!d.isAfter(today.plusDays(31))).min(LocalDate::compareTo).ifPresent(dates::add);
                if(dates.isEmpty()) dates.add(today.plusDays(1));
                for(var day:dates) prepare(account,day.toString(),actor==null?"SYSTEM":actor);
            } catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("사업장 초기 상품 준비 실패. 발주 화면에서 납품일별 준비 상태를 확인해주세요."); }
        }); } catch(RejectedExecutionException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("상품 준비 대기열이 가득 찼습니다. 날짜별 준비 화면에서 다시 시도해주세요."); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
