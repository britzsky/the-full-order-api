package com.example.demo.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 기존 ERP(thefull.kr) 본사 공지사항 — GET {erp.api.base-url}/HeadOffice/NoticeList (인자 없음, 배열).
 * PC 홈 B 영역(2026-11-09 사용자 요청). 브라우저가 ERP를 직접 부르면 CORS가 일부 출처만 허용해서 서버가 대신 읽는다(예산·위생과 같은 방식).
 * 게시 기한(expire_dt)이 지난 공지는 뺀다. 순서는 등록일 최신순.
 */
@Service
public class ErpNoticeClient {
	private static final Logger log = LoggerFactory.getLogger(ErpNoticeClient.class);
	private static final String[] FIELDS = { "idx", "title", "large_type", "large_type_nm", "middle_type", "middle_type_nm",
			"user_name", "position_name", "reg_dt", "expire_dt", "file_cnt" };
	private final WebClient client;
	private final ObjectMapper json;
	private final String baseUrl;
	private final Duration timeout;

	public ErpNoticeClient(WebClient.Builder builder, ObjectMapper json,
			@Value("${erp.api.base-url:https://thefull.kr/api}") String baseUrl,
			@Value("${erp.api.timeout-seconds:5}") long timeoutSeconds) {
		this.client = builder.clone().build();
		this.json = json;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
	}

	/** status: OK / ERROR(ERP 조회 실패). notices: 공지 목록(필요한 항목만) */
	public Map<String, Object> list() {
		try {
			String body = client.get().uri(baseUrl + "/HeadOffice/NoticeList")
					.retrieve().bodyToMono(String.class).block(timeout);
			return parse(body, LocalDate.now(ZoneId.of("Asia/Seoul")));
		} catch (RuntimeException e) {
			log.warn("ERP 공지사항 조회 실패: {}", e.getClass().getSimpleName());
			return Map.of("status", "ERROR", "notices", List.of());
		}
	}

	Map<String, Object> parse(String body, LocalDate today) {
		JsonNode root;
		try {
			root = json.readTree(body == null ? "" : body);
		} catch (Exception e) {
			return Map.of("status", "ERROR", "notices", List.of());
		}
		if (root == null || !root.isArray())
			return Map.of("status", "ERROR", "notices", List.of());
		List<Map<String, Object>> notices = new ArrayList<>();
		for (JsonNode row : root) {
			String expire = row.path("expire_dt").asText("");
			if (expire.length() >= 10 && expire.substring(0, 10).compareTo(today.toString()) < 0)
				continue;
			Map<String, Object> notice = new LinkedHashMap<>();
			for (String field : FIELDS) {
				JsonNode value = row.get(field);
				notice.put(field, value == null || value.isNull() ? null : value.isNumber() ? value.numberValue() : value.asText());
			}
			notices.add(notice);
		}
		notices.sort((a, b) -> String.valueOf(b.get("reg_dt")).compareTo(String.valueOf(a.get("reg_dt"))));
		return Map.of("status", "OK", "notices", notices);
	}
}
