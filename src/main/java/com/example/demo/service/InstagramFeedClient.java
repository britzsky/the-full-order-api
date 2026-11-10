package com.example.demo.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 더채움 인스타그램 최근 게시물 — GET {instagram.proxy-url}?limit=N (회사 홈페이지 thefull.co.kr의 프록시, 2026-11-10 사용자 지정).
 * PC 홈 C 영역. 그 프록시는 CORS 헤더가 없어 브라우저가 직접 못 부르므로 서버가 대신 읽는다(공지사항 ErpNoticeClient와 같은 방식).
 * 화면에 필요한 항목만 넘기고(사진·링크·날짜·글 첫 줄), 성공한 응답은 5분 동안 기억한다(프록시도 max-age=300).
 */
@Service
public class InstagramFeedClient {
	private static final Logger log = LoggerFactory.getLogger(InstagramFeedClient.class);
	static final int MAX_LIMIT = 12;
	private static final long CACHE_MILLIS = 5 * 60 * 1000L;
	private static final int CAPTION_MAX = 120;
	private final WebClient client;
	private final ObjectMapper json;
	private final String proxyUrl;
	private final Duration timeout;
	private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

	private record Cached(long at, Map<String, Object> value) {}

	public InstagramFeedClient(WebClient.Builder builder, ObjectMapper json,
			@Value("${instagram.proxy-url:https://thefull.co.kr/instagram-proxy}") String proxyUrl,
			@Value("${instagram.timeout-seconds:5}") long timeoutSeconds) {
		this.client = builder.clone().build();
		this.json = json;
		this.proxyUrl = proxyUrl;
		this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
	}

	/** status: OK / ERROR(조회 실패). posts: 최근 게시물(사진 있는 것만, 최대 limit개) */
	public Map<String, Object> recent(int limit) {
		int n = Math.max(1, Math.min(MAX_LIMIT, limit));
		Cached hit = cache.get(n);
		if (hit != null && System.currentTimeMillis() - hit.at() < CACHE_MILLIS)
			return hit.value();
		try {
			String body = client.get().uri(proxyUrl + "?limit=" + n).retrieve().bodyToMono(String.class).block(timeout);
			Map<String, Object> result = parse(body, n);
			if ("OK".equals(result.get("status")))
				cache.put(n, new Cached(System.currentTimeMillis(), result));
			return result;
		} catch (RuntimeException e) {
			log.warn("인스타그램 게시물 조회 실패: {}", e.getClass().getSimpleName());
			return Map.of("status", "ERROR", "posts", List.of());
		}
	}

	Map<String, Object> parse(String body, int limit) {
		JsonNode root;
		try {
			root = json.readTree(body == null ? "" : body);
		} catch (Exception e) {
			return Map.of("status", "ERROR", "posts", List.of());
		}
		if (root == null || !root.path("data").isArray())
			return Map.of("status", "ERROR", "posts", List.of());
		List<Map<String, Object>> posts = new ArrayList<>();
		for (JsonNode row : root.path("data")) {
			if (posts.size() >= limit)
				break;
			String type = row.path("media_type").asText("");
			// 동영상은 media_url이 영상 파일이라 미리보기 사진(thumbnail_url)을 쓴다
			String image = "VIDEO".equals(type) ? text(row, "thumbnail_url") : text(row, "media_url");
			String permalink = text(row, "permalink");
			if (image == null || permalink == null || !image.startsWith("https://") || !permalink.startsWith("https://"))
				continue;
			Map<String, Object> post = new LinkedHashMap<>();
			post.put("id", text(row, "id"));
			post.put("media_type", type);
			post.put("image", image);
			post.put("permalink", permalink);
			post.put("timestamp", text(row, "timestamp"));
			post.put("caption", firstLine(text(row, "caption")));
			posts.add(post);
		}
		return Map.of("status", "OK", "posts", posts);
	}

	private static String text(JsonNode row, String field) {
		JsonNode value = row.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}

	/** 글의 첫 줄(빈 줄 건너뜀), 길면 자름 — 사진 대체 글·마우스 올림 설명용 */
	static String firstLine(String caption) {
		if (caption == null)
			return "";
		for (String line : caption.split("\\R")) {
			String trimmed = line.strip();
			if (!trimmed.isEmpty())
				return trimmed.length() > CAPTION_MAX ? trimmed.substring(0, CAPTION_MAX) + "…" : trimmed;
		}
		return "";
	}
}
