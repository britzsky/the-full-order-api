package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.HygieneCheckMapper;

class HygieneCheckServiceTest {
    final HygieneCheckMapper mapper = mock(HygieneCheckMapper.class);
    final HygieneCheckService service = new HygieneCheckService(mapper);
    final String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();

    @Test void periodFollowsRecordRules() {
        assertThat(HygieneCheckService.range("2026", "02")).containsExactly("2026-02-01", "2026-02-28");
        assertThat(HygieneCheckService.range("2026", "")).containsExactly("2026-01-01", "2026-12-31");
        assertThat(HygieneCheckService.range("", "")).containsExactly("", "");
        assertThatThrownBy(() -> HygieneCheckService.range("2026", "13")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void markDefaultsToTodayWithUser() {
        service.mark("A", " fridge ", "", true, "site", "2026", "11");
        verify(mapper).mark("A", "fridge", today, "site");
        verify(mapper).list("A", "2026-11-01", "2026-11-30");
    }

    @Test void unmarkClearsTheWholePeriod() {
        service.mark("A", "fridge", "", false, "site", "2026", "11");
        verify(mapper).unmark("A", "fridge", "2026-11-01", "2026-11-30");
        verify(mapper, never()).mark(any(), any(), any(), any());
    }

    @Test void rejectsFutureDateAndMissingCategory() {
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString();
        assertThatThrownBy(() -> service.mark("A", "fridge", tomorrow, true, "u", "", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.mark("A", "", "", true, "u", "", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.checks("", "", "")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }
}
