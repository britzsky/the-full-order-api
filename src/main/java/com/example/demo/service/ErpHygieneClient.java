package com.example.demo.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 기존 ERP(thefull.kr) 위생관리 API 호출(2026-10-26 사용자 결정: 데이터는 ERP 한 곳 — 기존 앱 위생점검과 같은 테이블).
 * 카테고리 HygieneCategoryList/Save, 점검 기록 HygieneList(월)·HygieneCheckList(누적)·HygieneSave, 사진 OperateImgUpload.
 * 사진 경로는 '/image/hygiene/…'로 저장되고 {erp.api.base-url}/image/…로 열린다.
 */
@Service
public class ErpHygieneClient {
	private static final Logger log = LoggerFactory.getLogger(ErpHygieneClient.class);
	private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {};
	private final WebClient client;
	private final ObjectMapper json;
	private final String baseUrl;
	private final Duration timeout;

	public ErpHygieneClient(WebClient.Builder builder, ObjectMapper json,
			@Value("${erp.api.base-url:https://thefull.kr/api}") String baseUrl,
			@Value("${erp.api.hygiene-timeout-seconds:20}") long timeoutSeconds) {
		// 점검 기록 목록이 커질 수 있어 응답 버퍼를 넉넉히
		this.client = builder.clone().exchangeStrategies(ExchangeStrategies.builder()
				.codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build()).build();
		this.json = json;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
	}

	public String baseUrl() { return baseUrl; }

	public List<Map<String, Object>> categories(String accountId) {
		return rows(get("/Operate/HygieneCategoryList?account_id={a}", accountId));
	}

	/** 월 기록(등록일 기준). year·month가 비면 누적 기록(작성·수정자 이름 포함) */
	public List<Map<String, Object>> records(String accountId, String year, String month) {
		if (month == null || month.isBlank())
			return rows(get("/Operate/HygieneCheckList?account_id={a}", accountId));
		return rows(get("/Operate/HygieneList?account_id={a}&year={y}&month={m}", accountId, year, month));
	}

	public JsonNode saveCategories(Map<String, Object> body) {
		return result(post("/Operate/HygieneCategorySave", body));
	}

	public JsonNode saveRecords(List<Map<String, Object>> rows) {
		return result(post("/Operate/HygieneSave", rows));
	}

	/** 사진 올리기 → ERP가 돌려준 저장 경로(/image/hygiene/{날짜}/{거래처}/…) */
	public String uploadPhoto(byte[] bytes, String filename, String contentType, String day, String accountId) {
		MultipartBodyBuilder form = new MultipartBodyBuilder();
		form.part("file", new ByteArrayResource(bytes) { @Override public String getFilename() { return filename; } })
				.contentType(contentType == null || contentType.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType));
		form.part("type", "hygiene");
		form.part("gubun", day);
		form.part("folder", accountId);
		String body = call(() -> client.post().uri(baseUrl + "/Operate/OperateImgUpload").contentType(MediaType.MULTIPART_FORM_DATA)
				.body(BodyInserters.fromMultipartData(form.build())).retrieve().bodyToMono(String.class).block(timeout), "사진 올리기");
		JsonNode node = parse(body);
		String path = node.path("image_path").asText("");
		if (node.path("code").asInt(0) != 200 || path.isBlank())
			throw new IllegalStateException("ERP에 사진을 올리지 못했습니다. " + node.path("message").asText(""));
		return path;
	}

	private String get(String uri, Object... args) {
		return call(() -> client.get().uri(baseUrl + uri, args).retrieve().bodyToMono(String.class).block(timeout), "조회");
	}

	private String post(String uri, Object body) {
		return call(() -> client.post().uri(baseUrl + uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
				.retrieve().bodyToMono(String.class).block(timeout), "저장");
	}

	private String call(java.util.function.Supplier<String> request, String what) {
		try {
			return request.get();
		} catch (RuntimeException e) {
			log.warn("ERP 위생관리 {} 실패: {}", what, e.toString());
			throw new IllegalStateException("ERP 위생관리 서버에 " + what + " 요청을 하지 못했습니다. 잠시 후 다시 시도해주세요.");
		}
	}

	private List<Map<String, Object>> rows(String body) {
		try {
			List<Map<String, Object>> rows = json.readValue(body == null || body.isBlank() ? "[]" : body, ROWS);
			return rows == null ? List.of() : rows;
		} catch (Exception e) {
			throw new IllegalStateException("ERP 위생관리 응답을 읽지 못했습니다.");
		}
	}

	private JsonNode result(String body) {
		JsonNode node = parse(body);
		if (node.path("code").asInt(0) != 200)
			throw new IllegalArgumentException("ERP 위생관리 저장 실패: " + node.path("message").asText("응답 확인 필요"));
		return node;
	}

	private JsonNode parse(String body) {
		try {
			return json.readTree(body == null ? "" : body);
		} catch (Exception e) {
			throw new IllegalStateException("ERP 위생관리 응답을 읽지 못했습니다.");
		}
	}
}
