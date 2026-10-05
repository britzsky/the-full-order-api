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
    @Test void unknownWelstoryPeriodIsNotGuessedOrCalled() throws Exception {
        var db=new Database();db.supplier="WELSTORY";var ourhome=mock(OurhomeCatalogSyncService.class);var welstory=mock(WelstoryCatalogSyncService.class);
        var service=new CatalogPreparationService(db,ourhome,welstory);
        try {
            service.prepare("A",LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString(),"worker");
            assertThat(db.done.await(3,TimeUnit.SECONDS)).isTrue();assertThat(db.outcome).contains("유효 순기");verifyNoInteractions(ourhome,welstory);
        } finally { service.close(); }
    }
    @Test void rejectsUnboundedDatesBeforeDatabaseOrProviderWork() {
        var jdbc=mock(JdbcTemplate.class);var service=new CatalogPreparationService(jdbc,mock(OurhomeCatalogSyncService.class),mock(WelstoryCatalogSyncService.class));
        try { assertThatThrownBy(()->service.prepare("A",LocalDate.now().plusYears(1).toString(),"worker")).hasMessageContaining("31일");verifyNoInteractions(jdbc); }
        finally { service.close(); }
    }
}
