package com.example.demo.service;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

@Service
public class WelstoryItemLookupService {

	private static final DateTimeFormatter GUID_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
	private static final AtomicInteger GUID_SEQUENCE = new AtomicInteger();

	private final WebClient webClient;
	private final String apiBaseUrl;
	private final String tokenUrl;
	private final String revokeUrl;
	private final String realtimeItemUrl;
	private final String configuredAccessToken;
	private final String clientId;
	private final String clientSecret;
	private final Duration timeout;

	public WelstoryItemLookupService(
			WebClient.Builder webClientBuilder,
			@Value("${welstory.api.base-url:}") String apiBaseUrl,
			@Value("${welstory.api.token-url:}") String tokenUrl,
			@Value("${welstory.api.revoke-url:}") String revokeUrl,
			@Value("${welstory.api.realtime-item-url:}") String realtimeItemUrl,
			@Value("${welstory.api.access-token:}") String configuredAccessToken,
			@Value("${welstory.api.client-id:}") String clientId,
			@Value("${welstory.api.client-secret:}") String clientSecret,
			@Value("${welstory.api.timeout-seconds:10}") long timeoutSeconds) {
		this.webClient = webClientBuilder.build();
		this.apiBaseUrl = apiBaseUrl;
		this.tokenUrl = tokenUrl;
		this.revokeUrl = revokeUrl;
		this.realtimeItemUrl = realtimeItemUrl;
		this.configuredAccessToken = configuredAccessToken;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.timeout = Duration.ofSeconds(timeoutSeconds);
	}

	public JsonNode lookup(JsonNode request) {
		return call("/fdapi/service/payer-realtime-item", request);
	}

	public String accessToken() {
		if (!configuredAccessToken.isBlank()) return configuredAccessToken.trim();
		if (tokenUrl.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
			throw new IllegalStateException("WebSocket 인증을 위한 access-token 또는 토큰 발급 설정이 필요합니다.");
		}
		return issueAccessToken();
	}

	public JsonNode call(String apiPath, JsonNode request) {
		ensureConfigured(apiPath);
		String accessToken = accessToken();

		return webClient.post()
				.uri(resolveApiUrl(apiPath))
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.header("guid", createGuid())
				.bodyValue(request)
				.retrieve()
				.bodyToMono(JsonNode.class)
				.block(timeout);
	}

	public JsonNode revokeConfiguredToken() {
		if (configuredAccessToken.isBlank()) {
			throw new IllegalStateException("폐기할 welstory.api.access-token 설정이 없습니다.");
		}
		if (revokeUrl.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
			throw new IllegalStateException("토큰 폐기를 위한 revoke-url, client-id, client-secret 설정이 필요합니다.");
		}
		return webClient.post().uri(revokeUrl)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.accept(MediaType.APPLICATION_JSON)
				.body(BodyInserters.fromFormData("client_id", clientId)
						.with("client_secret", clientSecret)
						.with("token", configuredAccessToken.trim()))
				.retrieve().bodyToMono(JsonNode.class).block(timeout);
	}

	private String issueAccessToken() {
		URI uri = UriComponentsBuilder.fromUriString(tokenUrl)
				.queryParam("client_id", clientId)
				.queryParam("client_secret", clientSecret)
				.queryParam("scope", "oob")
				.queryParam("grant_type", "client_credentials")
				.build()
				.encode()
				.toUri();

		JsonNode response = webClient.post()
				.uri(uri)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.bodyToMono(JsonNode.class)
				.block(timeout);

		String token = response == null ? "" : response.path("access_token").asText("").trim();
		if (token.isEmpty()) {
			String description = response == null ? "" : response.path("error_description").asText("");
			throw new IllegalStateException(
					description.isBlank() ? "웰스토리 액세스 토큰 발급에 실패했습니다." : description);
		}
		return token;
	}

	private void ensureConfigured(String apiPath) {
		if (apiBaseUrl.isBlank() && realtimeItemUrl.isBlank()) {
			throw new IllegalStateException("welstory.api.base-url 설정이 필요합니다.");
		}
		if (!configuredAccessToken.isBlank()) return;
		if (tokenUrl.isBlank()) {
			throw new IllegalStateException("토큰이 없으므로 welstory.api.token-url 설정이 필요합니다.");
		}
		if (clientId.isBlank() || clientSecret.isBlank()) {
			throw new IllegalStateException(
					"토큰이 없으므로 welstory.api.client-id와 client-secret 설정이 필요합니다.");
		}
	}

	private String resolveApiUrl(String apiPath) {
		if ("/fdapi/service/payer-realtime-item".equals(apiPath) && !realtimeItemUrl.isBlank()) {
			return realtimeItemUrl;
		}
		String base = apiBaseUrl.endsWith("/")
				? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
				: apiBaseUrl;
		return base + (apiPath.startsWith("/") ? apiPath : "/" + apiPath);
	}

	private static String createGuid() {
		int sequence = GUID_SEQUENCE.updateAndGet(current -> current >= 99 ? 1 : current + 1);
		return LocalDateTime.now().format(GUID_DATE_TIME) + String.format("%02d", sequence);
	}
}
