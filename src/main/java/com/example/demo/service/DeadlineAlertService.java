package com.example.demo.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 발주 마감 알림 대상. 저장된 식단(PLAN) 기준 부족 식재료마다 우선(또는 유일한) 공급상품의 다음 발주 마감을
 * 공급사 가이드 규칙(OrderAvailability.deadline)으로 계산해 공급사·사업장·마감·납품일별로 묶는다.
 * 이미 발주한 수량은 부족량 계산(기발주 미입고)에서 빠지므로 발주를 마치면 자동으로 사라진다.
 * 마감 규칙을 모르는 상품, 기존 주문 확인이 필요한 품목, 판매중 후보가 없는 품목은 알리지 않는다(추정 금지).
 * upcoming: 알림 범위와 상관없이 식단 기준으로 지금 발주가 필요한 품목 수·가장 빠른 필요일·가장 빠른 마감(홈 '오늘 할 일'·종 알림).
 */
@Service
public class DeadlineAlertService {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int SEARCH_DAYS = 14;
    private final ShortageProcurementService shortages;

    public DeadlineAlertService(ShortageProcurementService shortages) { this.shortages = shortages; }

    public Map<String, Object> alerts(Map<String, Object> input) {
        return alerts(input, Clock.system(KST));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> alerts(Map<String, Object> input, Clock clock) {
        String account = Objects.toString(input.get("account_id"), "").trim();
        if (account.isEmpty()) throw new IllegalArgumentException("거래처를 선택해주세요.");
        int hours;
        try { hours = Integer.parseInt(Objects.toString(input.getOrDefault("within_hours", "24"), "24")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("알림 범위(시간)를 확인해주세요."); }
        if (hours < 1 || hours > 72) throw new IllegalArgumentException("알림 범위는 1~72시간입니다.");
        LocalDateTime now = clock.instant().atZone(KST).toLocalDateTime();
        LocalDate today = now.toLocalDate();
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("account_id", account);
        criteria.put("source", "PLAN");
        criteria.put("date_from", today.toString());
        criteria.put("date_to", today.plusDays(SEARCH_DAYS).toString());
        criteria.put("delivery_date", today.toString());
        var rows = (List<Map<String, Object>>) shortages.shortages(criteria).get("ingredients");

        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        int upcomingItems = 0;
        LocalDate upcomingNeeded = null;
        LocalDateTime upcomingDeadline = null;
        Map<String, Object> upcomingOffer = null;
        for (Map<String, Object> row : rows) {
            if (new BigDecimal(Objects.toString(row.get("shortage_qty"), "0")).signum() <= 0) continue;
            upcomingItems++;
            String neededRaw = Objects.toString(row.get("needed_by"), "");
            if (!neededRaw.isBlank()) {
                LocalDate needed = LocalDate.parse(neededRaw);
                if (upcomingNeeded == null || needed.isBefore(upcomingNeeded)) upcomingNeeded = needed;
            }
            if (!((Collection<?>) row.getOrDefault("issues", List.of())).isEmpty()) continue;
            Map<String, Object> offer = chosenOffer((List<Map<String, Object>>) row.getOrDefault("offers", List.of()));
            if (offer == null) continue;
            String neededText = Objects.toString(row.get("needed_by"), "");
            LocalDate neededBy = neededText.isBlank() ? today : LocalDate.parse(neededText);
            if (neededBy.isBefore(today)) neededBy = today;
            // 기준은 "필요한 날 받으려면 언제까지 주문해야 하는가"(마지막 기회). 마감은 매일 돌아오므로
            // 가장 가까운 마감을 쓰면 부족 품목이 있는 한 항상 알림이 된다.
            LocalDate delivery = neededBy;
            LocalDateTime deadline = OrderAvailability.deadline(offer, neededBy);
            if (deadline == null) continue;   // 마감 규칙 미확인
            if (!deadline.isAfter(now)) {
                // 필요일 납품분 마감이 이미 지남 → 가장 빨리 받을 수 있는 납품일의 마감(늦음 표시)
                deadline = null;
                for (int i = 0; i <= SEARCH_DAYS && deadline == null; i++) {
                    LocalDateTime candidate = OrderAvailability.deadline(offer, today.plusDays(i));
                    if (candidate.isAfter(now)) { delivery = today.plusDays(i); deadline = candidate; }
                }
            }
            if (deadline == null) continue;
            if (upcomingDeadline == null || deadline.isBefore(upcomingDeadline)) { upcomingDeadline = deadline; upcomingOffer = offer; }
            if (deadline.isAfter(now.plusHours(hours))) continue;
            String key = offer.get("supplier_code") + "|" + offer.get("external_site_code") + "|" + deadline + "|" + delivery;
            Map<String, Object> group = groups.get(key);
            if (group == null) {
                group = new LinkedHashMap<>();
                group.put("alert_key", key);
                group.put("supplier_code", offer.get("supplier_code"));
                group.put("supplier_name", offer.get("supplier_name"));
                group.put("external_site_code", offer.get("external_site_code"));
                group.put("external_site_name", offer.get("external_site_name"));
                group.put("deadline", deadline.toString());
                group.put("delivery_date", delivery.toString());
                group.put("minutes_left", Duration.between(now, deadline).toMinutes());
                group.put("rule", OrderAvailability.ruleLabel(offer));
                group.put("items", new ArrayList<Map<String, Object>>());
                groups.put(key, group);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ingredient_id", row.get("ingredient_id"));
            item.put("ingredient_name", row.get("ingredient_name"));
            item.put("product_name", offer.get("product_name"));
            item.put("shortage_qty", row.get("shortage_qty"));
            item.put("base_unit", row.get("base_unit"));
            item.put("stock_status", row.get("stock_status"));
            item.put("needed_by", neededBy.toString());
            // 이 마감의 납품일이 필요한 날보다 늦으면 화면에서 따로 표시한다(필요일 납품분 마감은 이미 지남).
            item.put("late", delivery.isAfter(neededBy));
            ((List<Map<String, Object>>) group.get("items")).add(item);
        }
        List<Map<String, Object>> result = new ArrayList<>(groups.values());
        result.sort(Comparator.comparing((Map<String, Object> g) -> g.get("deadline").toString()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("checked_at", now.toString());
        response.put("within_hours", hours);
        response.put("alerts", result);
        Map<String, Object> upcoming = new LinkedHashMap<>();
        upcoming.put("items", upcomingItems);
        upcoming.put("needed_from", upcomingNeeded == null ? null : (upcomingNeeded.isBefore(today) ? today : upcomingNeeded).toString());
        upcoming.put("first_deadline", upcomingDeadline == null ? null : upcomingDeadline.toString());
        upcoming.put("first_deadline_supplier", upcomingOffer == null ? null : upcomingOffer.get("supplier_name"));
        response.put("upcoming", upcoming);
        return response;
    }

    /**
     * 부족 발주 추천과 같은 기준: 우선 상품(이 식재료로 발주했던 상품)이 하나면 그것, 아니면 발주 화면이 먼저 보여 주는 첫 후보
     * (후보는 주문 가능·늘 쓰던·나만의 식재료·이름 일치·금액 순으로 정렬돼 온다). 처음 발주하는 사업장도 알림이 되도록. 판매중 상품만.
     * 후보는 공급사 사업장 상품(offer)뿐이라 직접구매 상품은 원래 들어오지 않는다. 마감 규칙이 없는 공급사는 deadline이 null이라 제외된다.
     */
    static Map<String, Object> chosenOffer(List<Map<String, Object>> offers) {
        List<Map<String, Object>> usable = offers.stream()
            .filter(o -> "ACTIVE".equals(o.get("product_status")))
            .toList();
        List<Map<String, Object>> preferred = usable.stream().filter(o -> "Y".equals(o.get("preferred_yn"))).toList();
        if (preferred.size() == 1) return preferred.get(0);
        return usable.isEmpty() ? null : usable.get(0);
    }
}
