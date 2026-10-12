package com.example.demo;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.example.demo.service.*;

/** 주요 화면 요청의 처리 시간·결과 크기 측정(읽기 전용 호출만, 트랜잭션 캐시 없이 매 호출 새 세션). PROFILE_ACCOUNT_ID로 거래처 지정. 공급사 호출 없음. */
@EnabledIfEnvironmentVariable(named="RUN_LOAD_PROFILE",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class LocalLoadProfileTest {
    @Autowired SupplierIntegrationService suppliers;
    @Autowired ShortageProcurementService shortages;
    @Autowired DeadlineAlertService alerts;
    @Autowired InventoryService inventory;
    @Autowired MenuService menus;
    @Autowired AccountMenuRecipeService recipes;
    @Autowired IngredientLinkSuggestionService suggestions;

    static String size(Object value) {
        if (value instanceof Collection<?> c) return c.size() + "건";
        if (value instanceof Map<?, ?> m) {
            for (String key : List.of("ingredients", "alerts", "suggestions")) if (m.get(key) instanceof Collection<?> c) return key + " " + c.size() + "건";
        }
        return "-";
    }
    void time(String label, Supplier<Object> call) {
        Runtime rt = Runtime.getRuntime();
        try {
            call.get(); // 1회 예열(커넥션·쿼리 캐시)
            long before = rt.totalMemory() - rt.freeMemory(), start = System.nanoTime();
            Object result = call.get();
            long ms = (System.nanoTime() - start) / 1_000_000, mem = (rt.totalMemory() - rt.freeMemory() - before) / 1024 / 1024;
            System.out.println("PROFILE " + label + " " + ms + "ms 결과=" + size(result) + " 힙증가≈" + mem + "MB");
        } catch (Exception e) { System.out.println("PROFILE " + label + " ERROR " + e); }
    }

    @Test void profile() {
        String account = System.getenv().getOrDefault("PROFILE_ACCOUNT_ID", "20250819193526");
        String today = LocalDate.now().toString(), tomorrow = LocalDate.now().plusDays(1).toString();
        time("offers(전체 사업장 상품)", () -> suppliers.offers(new HashMap<>(Map.of("account_id", account, "delivery_date", tomorrow))));
        time("shortages PLAN", () -> shortages.shortages(new HashMap<>(Map.of("account_id", account, "source", "PLAN", "date_from", today, "date_to", LocalDate.now().plusDays(6).toString(), "delivery_date", tomorrow))));
        time("shortages INVENTORY", () -> shortages.shortages(new HashMap<>(Map.of("account_id", account, "source", "INVENTORY", "delivery_date", tomorrow, "date_to", LocalDate.now().plusDays(6).toString()))));
        time("shortages STOCK", () -> shortages.shortages(new HashMap<>(Map.of("account_id", account, "source", "STOCK", "delivery_date", tomorrow, "date_to", LocalDate.now().plusDays(6).toString()))));
        time("deadline alerts", () -> alerts.alerts(new HashMap<>(Map.of("account_id", account, "within_hours", "6"))));
        time("inventory list(+부족 계산)", () -> inventory.AccountInventoryList(new HashMap<>(Map.of("account_id", account))));
        time("account menu list(원가)", () -> menus.AccountMenuList(new HashMap<>(Map.of("account_id", account))));
        time("account ingredients", () -> recipes.ingredients(new HashMap<>(Map.of("account_id", account))));
        try {
            var rows = suppliers.offers(new HashMap<>(Map.of("account_id", account, "delivery_date", tomorrow)));
            System.out.println("PROFILE offers JSON " + new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsBytes(rows).length / 1024 + "KB (" + rows.size() + "건)");
        } catch (Exception e) { System.out.println("PROFILE offers JSON ERROR " + e); }
        time("offers 검색 '돼지고기' 200건", () -> suppliers.offers(new HashMap<>(Map.of("account_id", account, "delivery_date", tomorrow, "keyword", "돼지고기", "limit", "200"))));
        try {
            var rows = suppliers.offers(new HashMap<>(Map.of("account_id", account, "delivery_date", tomorrow, "keyword", "돼지고기", "limit", "200")));
            System.out.println("PROFILE offers 검색 JSON " + new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsBytes(rows).length / 1024 + "KB (" + rows.size() + "건)");
        } catch (Exception e) { System.out.println("PROFILE offers 검색 JSON ERROR " + e); }
        if (!"N".equals(System.getenv("PROFILE_SUGGEST"))) time("link suggestions unlinked", () -> suggestions.unlinked(new HashMap<>(Map.of("account_id", account))));
    }
}
