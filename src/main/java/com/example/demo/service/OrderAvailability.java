package com.example.demo.service;

import java.time.*;
import java.util.Map;
import java.util.Objects;

/**
 * Shared ordering deadline policy. Provider codes are only treated as times when the provider guide defines them
 * (Welstory closeCode table below); unknown codes stay REVIEW_REQUIRED.
 */
public final class OrderAvailability {
    private OrderAvailability() {}

    /**
     * 웰스토리 연동 가이드의 closeCode 정의: 'M01' 육류 11시, 'N01' 신선품 17시, 'F01' 육류 17시, ' ' 해당없음.
     * 마감일은 납품일 − leadTime(일). 해당없음·미정의 코드는 시각을 추정하지 않는다.
     */
    static final Map<String, LocalTime> WELSTORY_CLOSE_TIMES = Map.of("M01", LocalTime.of(11, 0), "N01", LocalTime.of(17, 0), "F01", LocalTime.of(17, 0));
    static final Map<String, String> WELSTORY_CLOSE_LABELS = Map.of("M01", "육류 11시 마감", "N01", "신선품 17시 마감", "F01", "육류 17시 마감");

    /** 공급사 가이드로 확인된 마감 코드의 시각. 모르면 null. */
    public static LocalTime providerCutoff(Object supplierCode, Object cutoffCode) {
        if (!"WELSTORY".equals(Objects.toString(supplierCode, ""))) return null;
        return WELSTORY_CLOSE_TIMES.get(Objects.toString(cutoffCode, "").trim().toUpperCase());
    }
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
        LocalTime cutoff = cutoffTime(row);
        if (cutoff == null) return review();
        String label = "WELSTORY".equals(Objects.toString(row.get("supplier_code"), ""))
                ? WELSTORY_CLOSE_LABELS.getOrDefault(Objects.toString(row.get("cutoff_code"), "").trim().toUpperCase(), "") : "";
        String rule = label.isEmpty() ? "" : " · " + label + " (납품 " + lead + "일 전)";
        var deadline = deadlineDate.atTime(cutoff);
        if (!now.isBefore(deadline)) return blocked("발주 마감 " + deadline + " (한국시간)이 지났습니다" + rule + ". 납품일을 변경해주세요.", deadline.toString());
        return new Decision("AVAILABLE", "발주 마감 " + deadline + " (한국시간)" + rule + " · 접수 시 공급사 재검증", deadline.toString());
    }
    /** 저장된 마감시각, 없으면 공급사 가이드로 확인된 마감 코드의 시각. 모르면 null. */
    static LocalTime cutoffTime(Map<String,Object> row) {
        try { return LocalTime.parse(Objects.toString(row.get("cutoff_time"), "")); }
        catch (RuntimeException e) { return providerCutoff(row.get("supplier_code"), row.get("cutoff_code")); }
    }

    /**
     * 납품일의 발주 마감 일시(한국시간). evaluate와 같은 규칙: 준비기간 1~366일과 확인된 마감시각이 있어야 하며,
     * 0일(기본값)·미확인 시각이면 null(확인 필요). 판매중지 여부는 보지 않는다.
     */
    public static LocalDateTime deadline(Map<String,Object> row, LocalDate delivery) {
        int lead;
        try { lead = Integer.parseInt(Objects.toString(row.get("lead_time_days"), "")); }
        catch (RuntimeException e) { return null; }
        if (lead < 1 || lead > 366) return null;
        LocalTime cutoff = cutoffTime(row);
        return cutoff == null ? null : delivery.minusDays(lead).atTime(cutoff);
    }

    /** 사람이 읽는 마감 규칙. 예: "육류 11시 마감 (납품 1일 전)", "납품 2일 전 16:30" */
    public static String ruleLabel(Map<String,Object> row) {
        String lead = Objects.toString(row.get("lead_time_days"), "");
        String code = Objects.toString(row.get("cutoff_code"), "").trim().toUpperCase();
        if ("WELSTORY".equals(Objects.toString(row.get("supplier_code"), "")) && WELSTORY_CLOSE_LABELS.containsKey(code))
            return WELSTORY_CLOSE_LABELS.get(code) + " (납품 " + lead + "일 전)";
        LocalTime cutoff = cutoffTime(row);
        return "납품 " + lead + "일 전" + (cutoff == null ? "" : " " + cutoff);
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
