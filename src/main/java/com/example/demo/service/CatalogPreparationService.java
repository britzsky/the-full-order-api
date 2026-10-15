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
    private WelstoryCycleSyncService cycles;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCycles(WelstoryCycleSyncService cycles) { this.cycles=cycles; }
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
        prepare(account,date,actor,false);
        return status(account,date.toString());
    }
    /** inline=true면 호출 스레드에서 차례로 실행한다(새벽 배치 — 작업 대기열 16칸을 넘겨 실패 처리되지 않게). */
    private int[] prepare(String account,LocalDate date,String actor,boolean inline) {
        if(account==null||account.isBlank()) throw new IllegalArgumentException("거래처를 선택해주세요.");
        int[] counts=new int[3]; // 준비 완료, 실패, 건너뜀(이미 준비됨·진행 중)
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
            if(claimed==0) { counts[2]++; continue; }
            if(inline) { counts[run(site,date,actor,type)?0:1]++; continue; }
            try { executor.execute(()->run(site,date,actor,type)); }
            catch(RejectedExecutionException e) { failed(supplier,id,type,"다른 상품 준비가 진행 중입니다. 잠시 후 다시 시도해주세요."); }
        }
        return counts;
    }
    private boolean run(Map<String,Object> site,LocalDate date,String actor,String type) {
        Object id=site.get("supplier_account_site_id"), supplier=site.get("supplier_id");
        try {
            Map<String,Object> command=new HashMap<>();command.put("account_id",site.get("account_id"));command.put("user_id",actor);
            if("OURHOME".equals(site.get("supplier_code"))) {
                command.put("siteCode",site.get("external_site_code"));command.put("deliveryDate",date.toString());ourhome.sync(command);
            } else {
                // 납품일이 속한 순기를 웰스토리 응답 날짜로 찾아, 아직 안 받은 순기면 전체 판가를 받는다(이미 받았으면 그대로)
                if(cycles==null) throw new IllegalArgumentException("웰스토리 순기 동기화가 설정되지 않았습니다.");
                cycles.ensureCycle(site,date,actor);
            }
            jdbc.update("""
                UPDATE the_full_order.tb_supplier_sync_checkpoint SET last_succeeded_at=NOW(),last_result_code='READY',last_result_message='납품일 상품 준비 완료',consecutive_failures=0
                WHERE supplier_id=? AND supplier_account_site_id=? AND sync_type=?
                """,supplier,id,type);
            return true;
        } catch(RuntimeException e) { failed(supplier,id,type,e instanceof IllegalArgumentException ? e.getMessage() : "상품 준비에 실패했습니다. 본사 연동 설정과 공급사 응답을 확인해주세요."); return false; }
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
    /** 아워홈은 사업장마다 앞으로의 발주 가능일 중 가까운 날을 이만큼 미리 준비한다(납품일별 판가 — 날짜당 약 1.1만 건). */
    static final int OURHOME_DAYS_AHEAD=2;
    /**
     * 새벽 배치: ① 7일 안 작성 중 식단의 거래처·날짜 ② 아워홈이 연결된 거래처마다 가까운 발주 가능일.
     * 식단을 아직 안 올린 업장도 아워홈 상품이 준비되게 ②를 더한다. 이미 12시간 안에 준비한 날짜는 건너뛴다.
     */
    public Map<String,Object> scheduled() {
        var targets=new LinkedHashMap<String,TreeSet<LocalDate>>();
        for(var row:jdbc.queryForList("""
            SELECT DISTINCT m.account_id,m.meal_date FROM the_full_order.tb_account_meal_service m
            WHERE m.status='DRAFT' AND m.meal_date BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE,INTERVAL 7 DAY)
            ORDER BY m.meal_date,m.account_id LIMIT 200
            """)) targets.computeIfAbsent(row.get("account_id").toString(),k->new TreeSet<>()).add(LocalDate.parse(row.get("meal_date").toString()));
        String ourhomeError=null;
        try { ourhomeDates().forEach((account,dates)->targets.computeIfAbsent(account,k->new TreeSet<>()).addAll(dates)); }
        catch(RuntimeException e) { ourhomeError="아워홈 사업장·발주 가능일 조회 실패 — 식단 기준 날짜만 준비했습니다."; }
        int[] total=new int[3];
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        targets.forEach((account,dates)->{ for(var day:dates) {
            if(day.isBefore(today)||day.isAfter(today.plusDays(31))) continue;
            try { var c=prepare(account,day,"SYSTEM",true); for(int i=0;i<3;i++) total[i]+=c[i]; }
            catch(RuntimeException e) { total[1]++; }
        }});
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("accounts",targets.size());result.put("prepared",total[0]);result.put("failed",total[1]);result.put("skipped",total[2]);
        if(ourhomeError!=null) result.put("warning",ourhomeError);
        return result;
    }
    /** 아워홈 사업장 목록은 한 번만 조회해 거래처별 가까운 발주 가능일(내일 이후)을 고른다. */
    private Map<String,List<LocalDate>> ourhomeDates() {
        var links=jdbc.queryForList("""
            SELECT ss.account_id,ss.external_site_code FROM the_full_order.tb_supplier_account_site ss
            JOIN the_full_order.tb_supplier s ON s.supplier_id=ss.supplier_id
            WHERE ss.active_yn='Y' AND s.supplier_code='OURHOME'
            """);
        Map<String,List<LocalDate>> result=new LinkedHashMap<>();
        if(links.isEmpty()||sites==null) return result;
        var calendar=new HashMap<String,List<String>>();
        for(var site:sites.sites().sites()) calendar.put(site.siteCode(),site.deliveryDates());
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        for(var link:links) {
            var dates=calendar.getOrDefault(Objects.toString(link.get("external_site_code")),List.of()).stream().map(LocalDate::parse)
                .filter(d->d.isAfter(today)&&!d.isAfter(today.plusDays(31))).sorted().limit(OURHOME_DAYS_AHEAD).toList();
            if(!dates.isEmpty()) result.put(link.get("account_id").toString(),dates);
        }
        return result;
    }
    /** 지난 납품일의 준비 체크포인트 정리(7일 보관). 판가·판매상태 데이터는 원가 추정에 쓰므로 지우지 않는다. */
    public int cleanup() {
        return jdbc.update("""
            DELETE FROM the_full_order.tb_supplier_sync_checkpoint
            WHERE sync_type LIKE 'PREPARE\\_%' AND sync_type<CONCAT('PREPARE_',DATE_FORMAT(DATE_SUB(CURRENT_DATE,INTERVAL 7 DAY),'%Y-%m-%d'))
            """);
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
