package com.example.demo.service;

import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 기존 ERP(thefull.kr) 거래처 정보 — GET {erp.api.base-url}/Account/AccountInfoList_3?account_id.
 * 끼니 시간(breakfast_time·lunch_time·dinner_time·snack_time)은 발주 DB에 두지 않고 여기서 읽는다(2026-10-28 사용자 결정).
 */
@Service
public class ErpAccountClient {
	private static final Logger log = LoggerFactory.getLogger(ErpAccountClient.class);
	private final WebClient client;
	private final ObjectMapper json;
	private final String baseUrl;
	private final Duration timeout;

	public ErpAccountClient(WebClient.Builder builder, ObjectMapper json,
			@Value("${erp.api.base-url:https://thefull.kr/api}") String baseUrl,
			@Value("${erp.api.timeout-seconds:5}") long timeoutSeconds) {
		this.client = builder.clone().build();
		this.json = json;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
	}

	/** 끼니 코드 → 시간. ERP 조회 실패·거래처 없음이면 빈 값(서류는 시간 칸만 비고 그대로 만든다) */
	public Map<Integer, MealTimeParser.SlotTime> mealTimes(String accountId) {
		try {
			String body = client.get()
					.uri(baseUrl + "/Account/AccountInfoList_3?account_id={a}", accountId)
					.retrieve().bodyToMono(String.class).block(timeout);
			return parse(body, accountId);
		} catch (RuntimeException e) {
			log.warn("ERP 거래처 정보 조회 실패 account={}: {}", accountId, e.getClass().getSimpleName());
			return Map.of();
		}
	}

	Map<Integer, MealTimeParser.SlotTime> parse(String body, String accountId) {
		JsonNode root;
		try {
			root = json.readTree(body == null ? "" : body);
		} catch (Exception e) {
			return Map.of();
		}
		// 응답은 배열(또는 행 하나) — 요청한 거래처의 행만 쓴다
		JsonNode row = null;
		if (root != null && root.isArray()) {
			for (JsonNode candidate : root)
				if (row == null && accountId.equals(candidate.path("account_id").asText()))
					row = candidate;
		} else if (root != null && root.isObject() && accountId.equals(root.path("account_id").asText())) {
			row = root;
		}
		if (row == null) return Map.of();
		return MealTimeParser.resolve(text(row, "breakfast_time"), text(row, "lunch_time"),
				text(row, "dinner_time"), text(row, "snack_time"));
	}

	private static String text(JsonNode row, String field) {
		JsonNode value = row.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.asText();
	}
}
