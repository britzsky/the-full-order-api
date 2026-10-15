package com.example.demo.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 기존 ERP(thefull.kr) 예산관리 조회 — GET {erp.api.base-url}/Operate/BudgetManageMentList?account_id&year&month.
 * 사용자 지정 항목(2026-10-15): 식자재 예산 = budget_grant, 식자재 사용 = day_use_amount,
 * 소모품 예산 = supplies_budget, 소모품 사용 = supplies_used. 여유 = 예산 − 사용.
 */
@Service
public class ErpBudgetClient {
	private static final Logger log = LoggerFactory.getLogger(ErpBudgetClient.class);
	private final WebClient client;
	private final ObjectMapper json;
	private final String baseUrl;
	private final Duration timeout;

	public ErpBudgetClient(WebClient.Builder builder, ObjectMapper json,
			@Value("${erp.api.base-url:https://thefull.kr/api}") String baseUrl,
			@Value("${erp.api.timeout-seconds:5}") long timeoutSeconds) {
		this.client = builder.clone().build();
		this.json = json;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
	}

	/** status: OK(값 있음) / NONE(그 달 예산 미등록) / ERROR(ERP 조회 실패). 금액은 원 단위 정수. */
	public Map<String, Object> monthly(String accountId, int year, int month) {
		try {
			String body = client.get()
					.uri(baseUrl + "/Operate/BudgetManageMentList?account_id={a}&year={y}&month={m}", accountId, year, month)
					.retrieve().bodyToMono(String.class).block(timeout);
			return parse(body, accountId);
		} catch (RuntimeException e) {
			log.warn("ERP 예산 조회 실패 account={} {}-{}: {}", accountId, year, month, e.getClass().getSimpleName());
			return Map.of("status", "ERROR");
		}
	}

	Map<String, Object> parse(String body, String accountId) {
		JsonNode root;
		try {
			root = json.readTree(body == null ? "" : body);
		} catch (Exception e) {
			return Map.of("status", "ERROR");
		}
		// 응답은 배열 — 요청한 거래처의 행만 쓴다(다른 거래처 값을 잘못 보여주지 않게)
		JsonNode row = null;
		if (root != null && root.isArray())
			for (JsonNode candidate : root)
				if (row == null && accountId.equals(candidate.path("account_id").asText()))
					row = candidate;
		if (row == null)
			return Map.of("status", "NONE");
		long food = row.path("budget_grant").asLong(0), foodUsed = row.path("day_use_amount").asLong(0);
		long supplies = row.path("supplies_budget").asLong(0), suppliesUsed = row.path("supplies_used").asLong(0);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("status", food == 0 && supplies == 0 && foodUsed == 0 ? "NONE" : "OK");
		result.put("food_budget", food);
		result.put("food_used", foodUsed);
		result.put("food_remaining", food - foodUsed);
		result.put("supplies_budget", supplies);
		result.put("supplies_used", suppliesUsed);
		result.put("supplies_remaining", supplies - suppliesUsed);
		result.put("note", row.path("note").asText(""));
		return result;
	}
}
