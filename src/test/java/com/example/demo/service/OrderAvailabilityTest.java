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
    @Test void welstoryCloseCodesFollowProviderGuide() {
        // 10/2 10:59:59 KST 기준, 10/3 납품(leadTime 1일) → 마감일 10/2
        var meat=offer();meat.remove("cutoff_time");meat.put("supplier_code","WELSTORY");meat.put("cutoff_code","M01");
        var decision=OrderAvailability.evaluate(meat,LocalDate.of(2026,10,3),before);
        assertThat(decision.status()).isEqualTo("AVAILABLE");
        assertThat(decision.deadline()).isEqualTo("2026-10-02T11:00");
        assertThat(decision.reason()).isEqualTo("D-1 · 육류 11시 마감 · 마감 10/2 11:00");
        assertThat(OrderAvailability.ruleLabel(meat)).isEqualTo("D-1 · 육류 11시 마감");
        assertThat(OrderAvailability.evaluate(meat,LocalDate.of(2026,10,3),Clock.offset(before,Duration.ofSeconds(1))).status()).isEqualTo("UNAVAILABLE");
        for(String code:List.of("N01","F01")) {
            var row=new HashMap<>(meat);row.put("cutoff_code",code);
            assertThat(OrderAvailability.evaluate(row,LocalDate.of(2026,10,3),Clock.offset(before,Duration.ofHours(6))).deadline()).isEqualTo("2026-10-02T17:00");
        }
        var none=new HashMap<>(meat);none.put("cutoff_code"," ");
        assertThat(OrderAvailability.evaluate(none,LocalDate.of(2026,10,3),before).status()).as("해당없음은 시각을 추정하지 않음").isEqualTo("REVIEW_REQUIRED");
        var ourhome=new HashMap<>(meat);ourhome.put("supplier_code","OURHOME");
        assertThat(OrderAvailability.evaluate(ourhome,LocalDate.of(2026,10,3),before).status()).as("다른 공급사 코드는 웰스토리 표 미적용").isEqualTo("REVIEW_REQUIRED");
        assertThat(OrderAvailability.providerCutoff("WELSTORY","m01")).isEqualTo(LocalTime.of(11,0));
    }
    @Test void leadTimeViolationAndProviderStopRemainBlocked() {
        assertThat(OrderAvailability.evaluate(offer(),LocalDate.of(2026,10,2),before).status()).isEqualTo("UNAVAILABLE");
        var row=offer();row.put("orderable_yn","N");row.put("availability_reason","수주마감");
        assertThat(OrderAvailability.evaluate(row,LocalDate.of(2026,10,3),before).reason()).isEqualTo("수주마감");
        assertThatThrownBy(()->OrderAvailability.requireAvailable(row,LocalDate.now().plusDays(3),true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void reviewRequiresExplicitManualConfirmation() {
        var row=offer();row.remove("cutoff_time");
        assertThatThrownBy(()->OrderAvailability.requireAvailable(row,LocalDate.now().plusDays(3),false)).hasMessageContaining("미확인 · 본사 확인 요망");
        assertThatCode(()->OrderAvailability.requireAvailable(row,LocalDate.now().plusDays(3),true)).doesNotThrowAnyException();
    }
}
