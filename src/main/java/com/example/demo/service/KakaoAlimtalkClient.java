package com.example.demo.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 카카오 알림톡 발송 — 발송 대행사 Solapi(POST /messages/v4/send-many/detail, HMAC-SHA256 인증).
 * 대행사를 바꾸면 이 클래스만 바꾼다(KakaoNotificationService는 send 결과만 본다).
 * 인증: Authorization: HMAC-SHA256 apiKey=…, date=ISO8601(UTC), salt=매번 새 값, signature=hex(HMAC(secret, date+salt)).
 */
@Service
public class KakaoAlimtalkClient {
	private static final Logger log = LoggerFactory.getLogger(KakaoAlimtalkClient.class);
	private static final SecureRandom RANDOM = new SecureRandom();
	private final WebClient client;
	private final ObjectMapper json;
	private final String baseUrl, apiKey, apiSecret, channelId, sender;
	private final boolean smsFallback;
	private final Duration timeout;

	public record Result(boolean accepted, String messageId, String code, String message) {}

	public KakaoAlimtalkClient(WebClient.Builder builder, ObjectMapper json,
			@Value("${kakao.alimtalk.solapi.base-url:https://api.solapi.com}") String baseUrl,
			@Value("${kakao.alimtalk.solapi.api-key:}") String apiKey,
			@Value("${kakao.alimtalk.solapi.api-secret:}") String apiSecret,
			@Value("${kakao.alimtalk.channel-id:}") String channelId,
			@Value("${kakao.alimtalk.sender:}") String sender,
			@Value("${kakao.alimtalk.sms-fallback:false}") boolean smsFallback,
			@Value("${kakao.alimtalk.timeout-seconds:10}") long timeoutSeconds) {
		this.client = builder.clone().build();
		this.json = json;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.apiKey = apiKey.trim();
		this.apiSecret = apiSecret.trim();
		this.channelId = channelId.trim();
		this.sender = sender.replaceAll("\\D", "");
		this.smsFallback = smsFallback;
		this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
	}

	/** API 키·시크릿·카카오 채널(pfId)·발신번호가 모두 설정됐는지. */
	public boolean configured() {
		return !apiKey.isEmpty() && !apiSecret.isEmpty() && !channelId.isEmpty() && !sender.isEmpty();
	}

	/** 알림톡 1건 발송 요청. 대행사 접수 결과만 알 수 있다(실제 카카오 도달은 대행사 콘솔에서 확인). */
	public Result send(String phone, String templateId, Map<String, String> variables) {
		if (!configured())
			return new Result(false, null, "NOT_CONFIGURED", "알림톡 발송 설정(API 키·채널·발신번호)이 없습니다.");
		ObjectNode body = json.createObjectNode();
		body.put("showMessageList", true);
		ObjectNode message = body.putArray("messages").addObject();
		message.put("to", phone).put("from", sender).put("type", "ATA");
		ObjectNode kakao = message.putObject("kakaoOptions");
		kakao.put("pfId", channelId).put("templateId", templateId).put("disableSms", !smsFallback);
		ObjectNode vars = kakao.putObject("variables");
		variables.forEach((name, value) -> vars.put("#{" + name + "}", value == null ? "" : value));
		try {
			JsonNode response = client.post().uri(baseUrl + "/messages/v4/send-many/detail")
					.header("Authorization", authorization(Instant.now()))
					.contentType(MediaType.APPLICATION_JSON).bodyValue(body.toString())
					.retrieve().bodyToMono(JsonNode.class).block(timeout);
			return result(response);
		} catch (WebClientResponseException e) {
			JsonNode error = parse(e.getResponseBodyAsString());
			String code = error.path("errorCode").asText("HTTP_" + e.getStatusCode().value());
			String text = error.path("errorMessage").asText("알림톡 발송 요청이 거절되었습니다.");
			log.warn("알림톡 발송 거절 code={} message={}", code, text);
			return new Result(false, null, cut(code, 20), cut(text, 500));
		} catch (RuntimeException e) {
			log.warn("알림톡 발송 실패: {}", e.getClass().getSimpleName());
			return new Result(false, null, "ERROR", "알림톡 발송 서버 응답을 받지 못했습니다.");
		}
	}

	Result result(JsonNode response) {
		if (response == null)
			return new Result(false, null, "EMPTY", "알림톡 발송 응답이 비어 있습니다.");
		JsonNode failed = response.path("failedMessageList");
		if (failed.isArray() && !failed.isEmpty()) {
			JsonNode first = failed.get(0);
			return new Result(false, null, cut(first.path("statusCode").asText("FAILED"), 20),
					cut(first.path("statusMessage").asText("알림톡 접수 실패"), 500));
		}
		JsonNode accepted = response.path("messageList");
		String messageId = null, code = "2000", text = "정상 접수";
		if (accepted.isArray() && !accepted.isEmpty()) {
			messageId = accepted.get(0).path("messageId").asText(null);
			code = accepted.get(0).path("statusCode").asText(code);
			text = accepted.get(0).path("statusMessage").asText(text);
		} else if (accepted.isObject() && accepted.size() > 0) {
			// 응답 형식에 따라 messageList가 messageId를 키로 한 객체일 수 있다.
			var entry = accepted.properties().iterator().next();
			messageId = entry.getKey();
			code = entry.getValue().path("statusCode").asText(code);
			text = entry.getValue().path("statusMessage").asText(text);
		}
		if (messageId == null)
			messageId = response.path("groupInfo").path("groupId").asText(null);
		return new Result(true, cut(messageId, 100), cut(code, 20), cut(text, 500));
	}

	String authorization(Instant now) {
		String date = now.truncatedTo(ChronoUnit.SECONDS).toString();
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		String salt = HexFormat.of().formatHex(bytes);
		return "HMAC-SHA256 apiKey=" + apiKey + ", date=" + date + ", salt=" + salt + ", signature="
				+ signature(apiSecret, date, salt);
	}

	static String signature(String secret, String date, String salt) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal((date + salt).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException("알림톡 인증값을 만들 수 없습니다.", e);
		}
	}

	private JsonNode parse(String body) {
		try {
			return json.readTree(body == null || body.isBlank() ? "{}" : body);
		} catch (Exception e) {
			return json.createObjectNode();
		}
	}

	private static String cut(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}
}
