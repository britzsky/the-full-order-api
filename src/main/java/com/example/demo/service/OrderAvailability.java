package com.example.demo.service;

import java.time.*;
import java.util.Map;
import java.util.Objects;

/** Shared ordering deadline policy. Provider codes are never treated as verified times. */
public final class OrderAvailability {
    private OrderAvailability() {}
    public record Decision(String status, String reason, String deadline) {
        public boolean available() { return "AVAILABLE".equals(status); }
        public void apply(Map<String,Object> row) {
            row.put("orderability_status", status);
            row.put("orderability_reason", reason);
            row.put("order_deadline", deadline);
        }
    }
    public static Decision evaluate(Map<String,Object> row, LocalDate delivery) {
        return evaluate(row, delivery, Clock.system(ZoneId.of("Asia/Seoul")));
    }
    static Decision evaluate(Map<String,Object> row, LocalDate delivery, Clock clock) {
        var now = clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime();
        if (delivery.isBefore(now.toLocalDate())) return blocked("지난 납품일입니다. 납품일을 변경해주세요.", "");
        if ("N".equals(row.get("orderable_yn")) || !"ACTIVE".equals(row.get("product_status")))
            return blocked(Objects.toString(row.get("availability_reason"), "공급사 판매상태를 확인해주세요."), "");
        int lead;
        try {
            lead = Integer.parseInt(Objects.toString(row.get("lead_time_days"), ""));
            if (lead < 0 || lead > 366) return review();
        } catch (RuntimeException e) { return review(); }
        var deadlineDate = delivery.minusDays(lead);
        if (now.toLocalDate().isAfter(deadlineDate))
            return blocked("납품일 " + delivery + "의 준비기간 " + lead + "일을 확보할 수 없습니다. 납품일을 변경해주세요.", "");
        // Zero is also the legacy database default, so it cannot establish same-day delivery.
        if (lead == 0) return review();
        LocalTime cutoff;
        try { cutoff = LocalTime.parse(Objects.toString(row.get("cutoff_time"), "")); }
        catch (RuntimeException e) { return review(); }
        var deadline = deadlineDate.atTime(cutoff);
        if (!now.isBefore(deadline)) return blocked("발주 마감 " + deadline + " (한국시간)이 지났습니다. 납품일을 변경해주세요.", deadline.toString());
        return new Decision("AVAILABLE", "발주 마감 " + deadline + " (한국시간) · 접수 시 공급사 재검증", deadline.toString());
    }
    private static Decision review() {
        return new Decision("REVIEW_REQUIRED", "납품일별 준비기간·마감시각 확인 필요 · 본사에서 공급사 조건을 확인한 뒤 갱신해주세요.", "");
    }
    private static Decision blocked(String reason, String deadline) { return new Decision("UNAVAILABLE", reason, deadline); }
    public static void requireAvailable(Map<String,Object> row, LocalDate delivery, boolean confirmed) {
        var decision = evaluate(row, delivery);
        if ("UNAVAILABLE".equals(decision.status()) || (!decision.available() && !confirmed))
            throw new IllegalArgumentException(decision.reason());
    }
}
