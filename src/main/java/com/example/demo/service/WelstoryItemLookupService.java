package com.example.demo.service;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

@Service
public class WelstoryItemLookupService {

	private static final Logger log = LoggerFactory.getLogger(WelstoryItemLookupService.class);
	private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofMinutes(10);
	private static final Duration PUBLIC_IP_CACHE_DURATION = Duration.ofMinutes(10);
	private static final ZoneId LOG_ZONE = ZoneId.of("Asia/Seoul");

	private final WebClient webClient;
	private final WelstoryGuidGenerator guidGenerator;
	private final String apiBaseUrl;
	private final String tokenUrl;
	private final String revokeUrl;
	private final String realtimeItemUrl;
	private final String configuredAccessToken;
	private final String clientId;
	private final String clientSecret;
	private final String publicIpUrl;
	private final Duration timeout;
	private final HttpClient publicIpClient;

	private volatile CachedToken cachedToken;
	private volatile CachedPublicIp cachedPublicIp;

	public WelstoryItemLookupService(
			WebClient.Builder webClientBuilder,
			WelstoryGuidGenerator guidGenerator,
			@Value("${welstory.api.base-url:}") String apiBaseUrl,
			@Value("${welstory.api.token-url:}") String tokenUrl,
			@Value("${welstory.api.revoke-url:}") String revokeUrl,
			@Value("${welstory.api.realtime-item-url:}") String realtimeItemUrl,
			@Value("${welstory.api.access-token:}") String configuredAccessToken,
			@Value("${welstory.api.client-id:}") String clientId,
			@Value("${welstory.api.client-secret:}") String clientSecret,
			@Value("${welstory.logging.public-ip-url:https://api.ipify.org}") String publicIpUrl,
			@Value("${welstory.api.timeout-seconds:10}") long timeoutSeconds) {
		this.webClient = webClientBuilder.build();
		this.guidGenerator = guidGenerator;
		this.apiBaseUrl = apiBaseUrl.trim();
		this.tokenUrl = tokenUrl.trim();
		this.revokeUrl = revokeUrl.trim();
		this.realtimeItemUrl = realtimeItemUrl.trim();
		this.configuredAccessToken = configuredAccessToken.trim();
		this.clientId = clientId.trim();
		this.clientSecret = clientSecret.trim();
		this.publicIpUrl = publicIpUrl.trim();
		this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
		this.publicIpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	}

	public JsonNode lookup(JsonNode request) {
		return call("/fdapi/service/payer-realtime-item", request);
	}

	public JsonNode lookupSoldTo(JsonNode request) {
		return call("/fdapi/service/payer-rep-soldto", request);
	}

	public String accessToken() {
		if (!configuredAccessToken.isBlank()) return configuredAccessToken;
		ensureTokenConfiguration();

		CachedToken current = cachedToken;
		if (current != null && current.isUsable()) return current.value();
		return refreshAccessToken();
	}

	public JsonNode call(String apiPath, JsonNode request) {
		ensureConfigured(apiPath);
		String token = accessToken();
		try {
			return callOnce(apiPath, request, token);
		} catch (WebClientResponseException exception) {
			if (exception.getStatusCode() != HttpStatus.UNAUTHORIZED || !configuredAccessToken.isBlank()) {
				throw exception;
			}
			invalidate(token);
			return callOnce(apiPath, request, accessToken());
		}
	}

	public JsonNode revokeConfiguredToken() {
		String token = configuredAccessToken.isBlank()
				? cachedToken == null ? "" : cachedToken.value()
				: configuredAccessToken;
		if (token.isBlank()) throw new IllegalStateException("폐기할 웰스토리 access token이 없습니다.");
		if (revokeUrl.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
			throw new IllegalStateException("토큰 폐기를 위한 revoke-url, client-id, client-secret 설정이 필요합니다.");
		}

		Map<String, String> form = new LinkedHashMap<>();
		form.put("client_id", clientId);
		form.put("client_secret", clientSecret);
		form.put("token", token);
		logRequest("TOKEN_REVOKE", revokeUrl, MediaType.APPLICATION_FORM_URLENCODED_VALUE, form);
		long startedAt = System.nanoTime();
		Mono<JsonNode> responseMono = webClient.post().uri(revokeUrl)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.accept(MediaType.APPLICATION_JSON)
				.body(BodyInserters.fromFormData("client_id", clientId)
						.with("client_secret", clientSecret)
						.with("token", token))
				.exchangeToMono(clientResponse -> handleResponse("TOKEN_REVOKE", revokeUrl, "-", startedAt, clientResponse));
		JsonNode response = blockResponse(responseMono, "TOKEN_REVOKE", revokeUrl, "-", startedAt);
		invalidate(token);
		return response;
	}

	private JsonNode callOnce(String apiPath, JsonNode request, String token) {
		String url = resolveApiUrl(apiPath);
		String guid = guidGenerator.next();
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
		headers.put(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		headers.put("guid", guid);
		logRequest("API", url, headers, request, "JSON (UTF-8 HTTP request body)");
		long startedAt = System.nanoTime();
		Mono<JsonNode> responseMono = webClient.post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("guid", guid)
				.bodyValue(request)
				.exchangeToMono(response -> handleResponse("API", url, guid, startedAt, response));
		return blockResponse(responseMono, "API", url, guid, startedAt);
	}

	private synchronized String refreshAccessToken() {
		CachedToken current = cachedToken;
		if (current != null && current.isUsable()) return current.value();

		Map<String, String> form = new LinkedHashMap<>();
		form.put("client_id", clientId);
		form.put("client_secret", clientSecret);
		form.put("scope", "oob");
		form.put("grant_type", "client_credentials");
		logRequest("TOKEN_ISSUE", tokenUrl, MediaType.APPLICATION_FORM_URLENCODED_VALUE, form);
		long startedAt = System.nanoTime();
		Mono<JsonNode> responseMono = webClient.post()
				.uri(tokenUrl)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.accept(MediaType.APPLICATION_JSON)
				.body(BodyInserters.fromFormData("client_id", clientId)
						.with("client_secret", clientSecret)
						.with("scope", "oob")
						.with("grant_type", "client_credentials"))
				.exchangeToMono(clientResponse -> handleResponse("TOKEN_ISSUE", tokenUrl, "-", startedAt, clientResponse));
		JsonNode response = blockResponse(responseMono, "TOKEN_ISSUE", tokenUrl, "-", startedAt);

		String token = response == null ? "" : response.path("access_token").asText("").trim();
		if (token.isEmpty()) {
			String description = response == null ? "" : response.path("error_description").asText("");
			throw new IllegalStateException(description.isBlank()
					? "웰스토리 access token 발급에 실패했습니다."
					: description);
		}
		long expiresIn = Math.max(1, response.path("expires_in").asLong(3600));
		cachedToken = new CachedToken(token, Instant.now().plusSeconds(expiresIn));
		return token;
	}

	private synchronized void invalidate(String token) {
		if (cachedToken != null && cachedToken.value().equals(token)) cachedToken = null;
	}

	private void ensureConfigured(String apiPath) {
		boolean realtimeItemApi = "/fdapi/service/payer-realtime-item".equals(apiPath);
		if (apiBaseUrl.isBlank() && (!realtimeItemApi || realtimeItemUrl.isBlank())) {
			throw new IllegalStateException("welstory.api.base-url 설정이 필요합니다.");
		}
		if (configuredAccessToken.isBlank()) ensureTokenConfiguration();
	}

	private void ensureTokenConfiguration() {
		if (tokenUrl.isBlank()) throw new IllegalStateException("welstory.api.token-url 설정이 필요합니다.");
		if (clientId.isBlank() || clientSecret.isBlank()) {
			throw new IllegalStateException("welstory.api.client-id와 client-secret 설정이 필요합니다.");
		}
	}

	private String resolveApiUrl(String apiPath) {
		if ("/fdapi/service/payer-realtime-item".equals(apiPath) && !realtimeItemUrl.isBlank()) {
			return realtimeItemUrl;
		}
		String base = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
		return base + (apiPath.startsWith("/") ? apiPath : "/" + apiPath);
	}

	private void logRequest(String type, String url, String contentType, Object body) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put(HttpHeaders.CONTENT_TYPE, contentType);
		headers.put(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		logRequest(type, url, headers, body, "application/x-www-form-urlencoded (UTF-8 HTTP request body)");
	}

	private void logRequest(String type, String url, Map<String, String> headers, Object body, String wireFormat) {
		log.info("""

				========== WELSTORY OUTBOUND REQUEST ==========
				type        : {}
				calledAt    : {}
				localIp     : {}
				publicIp    : {}
				method      : POST
				endpoint    : {}
				headers     : {}
				body        : {}
				wireFormat  : {}
				===============================================""",
				type, OffsetDateTime.now(LOG_ZONE), resolveLocalIp(url), resolvePublicIp(), url, headers, body, wireFormat);
	}

	private Mono<JsonNode> handleResponse(
			String type, String url, String guid, long startedAt, ClientResponse response) {
		long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
		if (response.statusCode().isError()) {
			return response.createException().flatMap(exception -> {
				logResponse(type, url, guid, exception.getStatusCode().value(),
						exception.getHeaders(), exception.getResponseBodyAsString(), elapsedMs);
				return Mono.error(exception);
			});
		}

		return response.bodyToMono(JsonNode.class)
				.doOnNext(body -> logResponse(type, url, guid, response.statusCode().value(),
						response.headers().asHttpHeaders(), body, elapsedMs))
				.switchIfEmpty(Mono.fromRunnable(() -> logResponse(type, url, guid,
						response.statusCode().value(), response.headers().asHttpHeaders(), "<empty>", elapsedMs)));
	}

	private JsonNode blockResponse(
			Mono<JsonNode> responseMono, String type, String url, String guid, long startedAt) {
		try {
			return responseMono.block(timeout);
		} catch (WebClientResponseException exception) {
			// HTTP 오류 응답은 handleResponse에서 status/header/body까지 이미 기록한다.
			throw exception;
		} catch (RuntimeException exception) {
			logCommunicationFailure(type, url, guid, startedAt, exception);
			throw exception;
		}
	}

	private void logCommunicationFailure(
			String type, String url, String guid, long startedAt, RuntimeException exception) {
		long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
		log.error("""

				======= WELSTORY COMMUNICATION FAILURE ========
				type        : {}
				failedAt    : {}
				guid        : {}
				endpoint    : {}
				elapsedMs   : {}
				errorType   : {}
				message     : {}
				response    : <not received>
				===============================================""",
				type, OffsetDateTime.now(LOG_ZONE), guid, url, elapsedMs,
				exception.getClass().getName(), exception.getMessage(), exception);
	}

	private void logResponse(
			String type, String url, String guid, int status, HttpHeaders headers, Object body, long elapsedMs) {
		log.info("""

				========== WELSTORY INBOUND RESPONSE ==========
				type        : {}
				receivedAt  : {}
				guid        : {}
				endpoint    : {}
				status      : {}
				elapsedMs   : {}
				headers     : {}
				body        : {}
				===============================================""",
				type, OffsetDateTime.now(LOG_ZONE), guid, url, status, elapsedMs, headers, body);
	}

	private String resolveLocalIp(String url) {
		try {
			URI uri = URI.create(url);
			int port = uri.getPort() >= 0 ? uri.getPort() : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
			try (DatagramSocket socket = new DatagramSocket()) {
				socket.connect(InetAddress.getByName(uri.getHost()), port);
				return socket.getLocalAddress().getHostAddress();
			}
		} catch (Exception exception) {
			return "unknown (" + exception.getClass().getSimpleName() + ")";
		}
	}

	private String resolvePublicIp() {
		CachedPublicIp current = cachedPublicIp;
		if (current != null && current.isUsable()) return current.value();
		if (publicIpUrl.isBlank()) return "disabled";

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(publicIpUrl))
					.timeout(Duration.ofSeconds(2))
					.GET()
					.build();
			String value = publicIpClient.send(request, HttpResponse.BodyHandlers.ofString()).body().trim();
			if (value.isBlank() || value.length() > 45) return "unknown (invalid response)";
			cachedPublicIp = new CachedPublicIp(value, Instant.now().plus(PUBLIC_IP_CACHE_DURATION));
			return value;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return "unknown (" + exception.getClass().getSimpleName() + ")";
		} catch (Exception exception) {
			return "unknown (" + exception.getClass().getSimpleName() + ")";
		}
	}

	private record CachedToken(String value, Instant expiresAt) {
		boolean isUsable() {
			return Instant.now().plus(TOKEN_EXPIRY_MARGIN).isBefore(expiresAt);
		}
	}

	private record CachedPublicIp(String value, Instant expiresAt) {
		boolean isUsable() {
			return Instant.now().isBefore(expiresAt);
		}
	}
}
