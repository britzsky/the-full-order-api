package com.example.demo.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderAvailabilityTest {
    private final Clock before = Clock.fixed(Instant.parse("2026-10-02T01:59:59Z"),ZoneOffset.UTC);
    private Map<String,Object> offer() { return new HashMap<>(Map.of("product_status","ACTIVE","orderable_yn","Y","lead_time_days",1,"cutoff_time","11:00:00")); }
    @Test void cutoffBoundaryUsesKoreaEvenWhenServerClockIsUtc() {
        var date=LocalDate.of(2026,10,3);
        assertThat(OrderAvailability.evaluate(offer(),date,before).status()).isEqualTo("AVAILABLE");
        assertThat(OrderAvailability.evaluate(offer(),date,Clock.offset(before,Duration.ofSeconds(1))).status()).isEqualTo("UNAVAILABLE");
    }
    @Test void laterDeliveryIsNotBlockedByTodaysCutoff() {
        assertThat(OrderAvailability.evaluate(offer(),LocalDate.of(2026,10,4),Clock.offset(before,Duration.ofHours(8))).status()).isEqualTo("AVAILABLE");
    }
    @Test void missingTimeAndDefaultZeroAreReviewNotSameDayGuarantees() {
        var row=offer();row.remove("cutoff_time");row.put("cutoff_code","M01");
        assertThat(OrderAvailability.evaluate(row,LocalDate.of(2026,10,3),before).status()).isEqualTo("REVIEW_REQUIRED");
        row.put("cutoff_time","11:00");row.put("lead_time_days",0);
        assertThat(OrderAvailability.evaluate(row,LocalDate.of(2026,10,2),before).status()).isEqualTo("REVIEW_REQUIRED");
    }
    @Test void leadTimeViolationAndProviderStopRemainBlocked() {
        assertThat(OrderAvailability.evaluate(offer(),LocalDate.of(2026,10,2),before).status()).isEqualTo("UNAVAILABLE");
        var row=offer();row.put("orderable_yn","N");row.put("availability_reason","수주마감");
        assertThat(OrderAvailability.evaluate(row,LocalDate.of(2026,10,3),before).reason()).isEqualTo("수주마감");
        assertThatThrownBy(()->OrderAvailability.requireAvailable(row,LocalDate.now().plusDays(3),true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void reviewRequiresExplicitManualConfirmation() {
        var row=offer();row.remove("cutoff_time");
        assertThatThrownBy(()->OrderAvailability.requireAvailable(row,LocalDate.now().plusDays(3),false)).hasMessageContaining("확인 필요");
        assertThatCode(()->OrderAvailability.requireAvailable(row,LocalDate.now().plusDays(3),true)).doesNotThrowAnyException();
    }
}
