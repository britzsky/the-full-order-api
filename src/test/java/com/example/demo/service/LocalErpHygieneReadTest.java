package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 실제 ERP 위생관리 조회만(저장·사진 올리기 없음). ERP_HYGIENE_ACCOUNT(기본 우리누리) */
@EnabledIfEnvironmentVariable(named = "RUN_ERP_HYGIENE_READ", matches = "true")
class LocalErpHygieneReadTest {
    @Test void readsRealRecords() {
        var erp = new ErpHygieneClient(WebClient.builder(), new ObjectMapper(), "https://thefull.kr/api", 20);
        String account = System.getenv().getOrDefault("ERP_HYGIENE_ACCOUNT", "20250819193526");
        var all = erp.records(account, "", "");
        System.out.println("ERP_HYGIENE all=" + all.size() + " categories=" + erp.categories(account).size()
                + " month2026-01=" + erp.records(account, "2026", "01").size() + " first=" + (all.isEmpty() ? "" : all.get(0).keySet()));
        assertThat(all).isNotEmpty();
        assertThat(all.get(0)).containsKeys("idx", "problem_image", "problem_note", "clean_image", "clean_note", "reg_dt");
    }
}
