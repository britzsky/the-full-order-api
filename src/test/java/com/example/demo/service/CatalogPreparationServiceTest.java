package com.example.demo.service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CatalogPreparationServiceTest {
    static class Database extends JdbcTemplate {
        final AtomicBoolean claimed=new AtomicBoolean();final CountDownLatch done=new CountDownLatch(1);
        String supplier="OURHOME";volatile String outcome="";
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
            if(sql.contains("SELECT ss.*,s.supplier_code")) return List.of(Map.of("supplier_code",supplier,"supplier_id",1,"supplier_account_site_id",2,"account_id","A","external_site_code","SITE"));
            return List.of();
        }
        @Override public int update(String sql,Object... args) {
            if(sql.contains("SET last_started_at")) return claimed.compareAndSet(false,true)?1:0;
            if(sql.contains("SET last_succeeded_at")) {outcome="READY";done.countDown();}
            if(sql.contains("SET last_failed_at")) {outcome=Objects.toString(args[0]);done.countDown();}
            return 1;
        }
    }
    @Test void concurrentPreparationClaimsOneProviderFetchAndStoresCompletion() throws Exception {
        var db=new Database();var ourhome=mock(OurhomeCatalogSyncService.class);var welstory=mock(WelstoryCatalogSyncService.class);
        var service=new CatalogPreparationService(db,ourhome,welstory);
        try {
            String date=LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString();
            service.prepare("A",date,"worker");service.prepare("A",date,"worker");
            assertThat(db.done.await(3,TimeUnit.SECONDS)).isTrue();assertThat(db.outcome).isEqualTo("READY");
            verify(ourhome,times(1)).sync(argThat(p->date.equals(p.get("deliveryDate"))&&"SITE".equals(p.get("siteCode"))));verifyNoInteractions(welstory);
        } finally { service.close(); }
    }
    @Test void welstoryPreparationFindsCycleForDeliveryDate() throws Exception {
        var db=new Database();db.supplier="WELSTORY";var ourhome=mock(OurhomeCatalogSyncService.class);var welstory=mock(WelstoryCatalogSyncService.class);
        var cycles=mock(WelstoryCycleSyncService.class);
        var service=new CatalogPreparationService(db,ourhome,welstory);service.setCycles(cycles);
        try {
            LocalDate date=LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
            service.prepare("A",date.toString(),"worker");
            assertThat(db.done.await(3,TimeUnit.SECONDS)).isTrue();assertThat(db.outcome).isEqualTo("READY");
            verify(cycles).ensureCycle(argThat(site->"SITE".equals(site.get("external_site_code"))),eq(date),eq("worker"));verifyNoInteractions(ourhome);
        } finally { service.close(); }
    }
    @Test void welstoryCycleNotOpenYetIsReportedNotGuessed() throws Exception {
        var db=new Database();db.supplier="WELSTORY";var cycles=mock(WelstoryCycleSyncService.class);
        when(cycles.ensureCycle(any(),any(),any())).thenThrow(new IllegalArgumentException("웰스토리에서 납품일의 순기 판가를 아직 받을 수 없습니다."));
        var service=new CatalogPreparationService(db,mock(OurhomeCatalogSyncService.class),mock(WelstoryCatalogSyncService.class));service.setCycles(cycles);
        try {
            service.prepare("A",LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString(),"worker");
            assertThat(db.done.await(3,TimeUnit.SECONDS)).isTrue();assertThat(db.outcome).contains("아직 받을 수 없습니다");
        } finally { service.close(); }
    }
    @Test void rejectsUnboundedDatesBeforeDatabaseOrProviderWork() {
        var jdbc=mock(JdbcTemplate.class);var service=new CatalogPreparationService(jdbc,mock(OurhomeCatalogSyncService.class),mock(WelstoryCatalogSyncService.class));
        try { assertThatThrownBy(()->service.prepare("A",LocalDate.now().plusYears(1).toString(),"worker")).hasMessageContaining("31일");verifyNoInteractions(jdbc); }
        finally { service.close(); }
    }
    @Test void scheduledPreparesNearestOurhomeDatesInlineEvenWithoutMealPlan() {
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        var prepared=new ArrayList<String>();
        var db=new JdbcTemplate() {
            @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
                if(sql.contains("SELECT ss.account_id,ss.external_site_code")) return List.of(Map.of("account_id","A","external_site_code","SITE"));
                if(sql.contains("SELECT ss.*,s.supplier_code")) return List.of(Map.of("supplier_code","OURHOME","supplier_id",1,"supplier_account_site_id",2,"account_id","A","external_site_code","SITE"));
                return List.of();
            }
            @Override public List<Map<String,Object>> queryForList(String sql) { return queryForList(sql,new Object[0]); }
            @Override public int update(String sql,Object... args) { return 1; }
        };
        var ourhome=mock(OurhomeCatalogSyncService.class);
        when(ourhome.sync(any())).thenAnswer(i->{prepared.add(((Map<?,?>)i.getArgument(0)).get("deliveryDate").toString());return Map.of();});
        var sites=mock(OurhomeSiteService.class);
        when(sites.sites()).thenReturn(new OurhomeSiteService.SiteResult("now",List.of(new OurhomeSiteService.Site("SITE","n","h","h","c","c","r","r",
            List.of(today.toString(),today.plusDays(3).toString(),today.plusDays(1).toString(),today.plusDays(5).toString())))));
        var service=new CatalogPreparationService(db,ourhome,mock(WelstoryCatalogSyncService.class));service.setSites(sites);
        try {
            var result=service.scheduled();
            // 오늘은 제외, 가까운 2일만 — 대기열이 아니라 배치 스레드에서 바로 끝난다
            assertThat(prepared).containsExactly(today.plusDays(1).toString(),today.plusDays(3).toString());
            assertThat(result).containsEntry("prepared",2).containsEntry("failed",0);
        } finally { service.close(); }
    }
}
