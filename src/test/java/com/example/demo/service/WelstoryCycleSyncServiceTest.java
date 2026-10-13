package com.example.demo.service;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WelstoryCycleSyncServiceTest {
    /** 웰스토리 응답을 흉내: 2026년 순기 n은 2026-01-05부터 3주씩, 14순기까지만 열려 있음 */
    static LocalDate[] dates(int group) {
        LocalDate from = LocalDate.of(2026, 1, 5).plusDays(21L * (group - 1));
        return new LocalDate[] { from, from.plusDays(20) };
    }
    static final Map<String, Object> SITE = Map.of("supplier_account_site_id", 7L, "supplier_id", 1L, "account_id", "A", "external_site_code", "A0199240");

    WelstoryCatalogSyncService provider(int openUntil) {
        var sync = mock(WelstoryCatalogSyncService.class);
        when(sync.periodDates(eq("A0199240"), anyString(), anyString())).thenAnswer(inv -> {
            int year = Integer.parseInt(inv.getArgument(1)), group = Integer.parseInt(inv.getArgument(2));
            return year == 2026 && group >= 1 && group <= openUntil ? dates(group) : null;
        });
        return sync;
    }

    JdbcTemplate emptyDb() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of());
        return jdbc;
    }

    @Test void findsCycleFromProviderDatesNotCalendar() {
        var sync = provider(14);
        var service = new WelstoryCycleSyncService(emptyDb(), sync);
        LocalDate date = LocalDate.of(2026, 10, 13);
        var cycle = service.find(SITE, date, new HashMap<>());
        assertThat(cycle).isNotNull();
        assertThat(cycle.covers(date)).isTrue();
        assertThat(cycle.revision()).isEqualTo("2026-14");
        assertThat(cycle.from()).isEqualTo(dates(14)[0]);
        verify(sync, atMost(6)).periodDates(any(), any(), any());
    }

    @Test void returnsNullWhenCycleNotOpenYet() {
        var service = new WelstoryCycleSyncService(emptyDb(), provider(13));
        assertThat(service.find(SITE, dates(14)[0].plusDays(3), new HashMap<>())).isNull();
    }

    @Test void nextCycleOnlyWhenProviderOpenedIt() {
        var current = new WelstoryCycleSyncService.Cycle(2026, 14, dates(14)[0], dates(14)[1]);
        assertThat(new WelstoryCycleSyncService(emptyDb(), provider(14)).next(SITE, current, new HashMap<>())).isNull();
        var next = new WelstoryCycleSyncService(emptyDb(), provider(15)).next(SITE, current, new HashMap<>());
        assertThat(next.revision()).isEqualTo("2026-15");
    }

    @Test void newCycleGetsFullSyncAndSyncedCycleOnlyChangedPrices() {
        var jdbc = emptyDb();
        var sync = provider(14);
        when(sync.syncCatalog(any())).thenReturn(Map.of("saved_count", 10));
        var service = new WelstoryCycleSyncService(jdbc, sync);
        LocalDate today = dates(14)[0].plusDays(2);
        var result = service.syncSite(SITE, today, "SYSTEM", new HashMap<>());
        verify(sync).syncCatalog(argThat(c -> "2026".equals(c.get("period_group_year")) && "14".equals(c.get("period_group")) && "A0199240".equals(c.get("sold_to"))));
        verify(sync, never()).syncChangedPrices(any());
        assertThat(result.get("next")).isEqualTo(Map.of("status", "NOT_OPEN"));

        // 이미 전체 판가를 받은 순기: 변경 판가만, 요청일은 오늘
        when(jdbc.queryForList(contains("last_result_code FROM"), eq(String.class), any(Object[].class))).thenReturn(List.of("SUCCESS"));
        when(sync.syncChangedPrices(any())).thenReturn(Map.of("saved_count", 2));
        service.syncSite(SITE, today, "SYSTEM", new HashMap<>());
        verify(sync).syncChangedPrices(argThat(c -> "14".equals(c.get("period_group")) && today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).equals(c.get("req_date"))));
        verify(sync, times(1)).syncCatalog(any());
    }
}
