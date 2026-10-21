package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.example.demo.mapper.AiUsageMapper;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * OpenAI Responses API 공용 호출기. 호출마다(성공·실패 모두) 토큰 수와 예상 비용을 tb_ai_usage_log에 남긴다.
 * 예상 비용 = 호출 당시 설정 단가(openai.price.*, 100만 토큰당 USD) × 토큰. 실제 청구액은 OpenAI 사용량 화면이 기준이다.
 * 하루 전체 예상 비용이 openai.daily-limit-usd 이상이면 호출하지 않는다(0이면 한도 없음).
 */
@Service
public class OpenAiClient {
	private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";
	private static final BigDecimal MILLION = new BigDecimal("1000000");

	private final String apiKey;
	private final String model;
	private final String reasoningEffort;
	private final BigDecimal inputPrice;
	private final BigDecimal cachedInputPrice;
	private final BigDecimal outputPrice;
	private final BigDecimal dailyLimit;
	private final WebClient webClient;
	private final AiUsageMapper usageMapper;

	public OpenAiClient(@Value("${openai.api.key:}") String apiKey,
			@Value("${openai.model:gpt-5.4-mini}") String model,
			@Value("${openai.reasoning-effort:low}") String reasoningEffort,
			@Value("${openai.price.input-per-1m:0.75}") BigDecimal inputPrice,
			@Value("${openai.price.cached-input-per-1m:0.075}") BigDecimal cachedInputPrice,
			@Value("${openai.price.output-per-1m:4.50}") BigDecimal outputPrice,
			@Value("${openai.daily-limit-usd:5}") BigDecimal dailyLimit,
			WebClient.Builder webClientBuilder, AiUsageMapper usageMapper) {
		this.apiKey = apiKey;
		this.model = model;
		this.reasoningEffort = reasoningEffort;
		this.inputPrice = inputPrice;
		this.cachedInputPrice = cachedInputPrice;
		this.outputPrice = outputPrice;
		this.dailyLimit = dailyLimit;
		this.webClient = webClientBuilder.codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build();
		this.usageMapper = usageMapper;
	}

	/** 누가·어떤 기능으로 호출했는지(이력 기록용) */
	public record Caller(String feature, String accountId, String userId, int itemCount) {
	}

	public boolean configured() {
		return apiKey != null && !apiKey.isBlank();
	}

	public String model() {
		return model;
	}

	/**
	 * system·user 메시지를 보내고 출력 글자(output_text)를 돌려준다.
	 * jsonSchema가 있으면 구조화 출력(strict JSON)으로 요청한다.
	 * 실패하면 IllegalStateException(화면에 보여 줄 문구)을 던지고, 실패도 이력에 남긴다.
	 */
	public String respond(Caller caller, String system, String user, String schemaName, Map<String, Object> jsonSchema) {
		if (!configured())
			throw new IllegalStateException("AI 키(openai.api.key)가 설정되지 않아 AI 기능을 쓸 수 없습니다.");
		if (dailyLimit.signum() > 0) {
			BigDecimal today = usageMapper.todayCost();
			if (today != null && today.compareTo(dailyLimit) >= 0)
				throw new IllegalStateException("오늘 AI 사용 한도(약 $" + dailyLimit.stripTrailingZeros().toPlainString() + ")에 도달했습니다. 내일 다시 시도하거나 본사에 한도 조정을 요청해주세요.");
		}
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("model", model);
		request.put("input", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
		if (reasoningEffort != null && !reasoningEffort.isBlank())
			request.put("reasoning", Map.of("effort", reasoningEffort));
		if (jsonSchema != null)
			request.put("text", Map.of("format", Map.of("type", "json_schema", "name", schemaName, "strict", true, "schema", jsonSchema)));

		long started = System.currentTimeMillis();
		JsonNode response = null;
		String error = null;
		String shown = null;
		try {
			response = webClient.post().uri(RESPONSES_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(request)
					.retrieve()
					.bodyToMono(JsonNode.class)
					.block(java.time.Duration.ofSeconds(120));
		} catch (WebClientResponseException e) {
			error = "AI 요청 실패(" + e.getStatusCode().value() + "): " + e.getResponseBodyAsString();
			int code = e.getStatusCode().value();
			shown = code == 401 || code == 403 ? "AI 키가 올바르지 않거나 만료되었습니다(" + code + "). 본사에 AI 키 확인을 요청해주세요."
					: code == 429 ? "AI 요청이 너무 많거나 OpenAI 결제 한도를 넘었습니다(429). 잠시 뒤 다시 시도하거나 본사에 확인을 요청해주세요."
					: "AI 요청이 실패했습니다(" + code + "). 잠시 뒤 다시 시도해주세요.";
		} catch (RuntimeException e) {
			error = "AI 호출 오류: " + e.getMessage();
			shown = "AI 응답을 받지 못했습니다(시간 초과 등). 잠시 뒤 다시 시도해주세요.";
		}
		String text = outputText(response);
		if (error == null && text == null) {
			error = "AI 응답이 비어 있습니다." + incompleteReason(response);
			shown = error;
		}
		log(caller, response, error, System.currentTimeMillis() - started);
		if (shown != null)
			throw new IllegalStateException(shown);
		return text;
	}

	/** 이력 한 줄. 기록 실패가 업무(AI 결과)를 막지 않게 한다 */
	private void log(Caller caller, JsonNode response, String error, long durationMs) {
		JsonNode usage = response == null ? null : response.path("usage");
		int input = usage == null ? 0 : usage.path("input_tokens").asInt(0);
		int cached = usage == null ? 0 : usage.path("input_tokens_details").path("cached_tokens").asInt(0);
		int output = usage == null ? 0 : usage.path("output_tokens").asInt(0);
		int reasoning = usage == null ? 0 : usage.path("output_tokens_details").path("reasoning_tokens").asInt(0);
		Map<String, Object> row = new HashMap<>();
		row.put("feature", caller.feature());
		row.put("account_id", blankToNull(caller.accountId()));
		row.put("user_id", blankToNull(caller.userId()));
		row.put("model", response != null && response.hasNonNull("model") ? response.get("model").asText() : model);
		row.put("item_count", Math.max(1, caller.itemCount()));
		row.put("input_tokens", input);
		row.put("cached_input_tokens", cached);
		row.put("output_tokens", output);
		row.put("reasoning_tokens", reasoning);
		row.put("cost_usd", cost(input, cached, output));
		row.put("status", error == null ? "SUCCESS" : "FAILED");
		row.put("error_message", error == null ? null : shorten(error));
		row.put("duration_ms", (int) Math.min(Integer.MAX_VALUE, durationMs));
		try {
			usageMapper.insert(row);
		} catch (RuntimeException ignored) {
			// 이력 테이블이 아직 없는 DB 등 — AI 결과는 그대로 돌려준다
		}
	}

	/** 캐시된 입력은 할인 단가, 나머지 입력·출력(추론 포함)은 기본 단가 */
	BigDecimal cost(int input, int cached, int output) {
		BigDecimal uncached = BigDecimal.valueOf(Math.max(0, input - cached)).multiply(inputPrice);
		BigDecimal cachedCost = BigDecimal.valueOf(cached).multiply(cachedInputPrice);
		BigDecimal outputCost = BigDecimal.valueOf(output).multiply(outputPrice);
		return uncached.add(cachedCost).add(outputCost).divide(MILLION, 6, RoundingMode.HALF_UP);
	}

	private static String outputText(JsonNode response) {
		if (response == null)
			return null;
		for (JsonNode output : response.path("output"))
			for (JsonNode content : output.path("content"))
				if ("output_text".equals(content.path("type").asText()) && !content.path("text").asText().isBlank())
					return content.path("text").asText();
		return null;
	}

	private static String incompleteReason(JsonNode response) {
		String reason = response == null ? "" : response.path("incomplete_details").path("reason").asText("");
		return reason.isBlank() ? "" : " (" + reason + ")";
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static String shorten(String value) {
		return value == null ? null : value.length() > 480 ? value.substring(0, 480) + "…" : value;
	}
}
