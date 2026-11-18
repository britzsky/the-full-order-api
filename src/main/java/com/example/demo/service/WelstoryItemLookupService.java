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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;

@Service
public class WelstoryItemLookupService {

	private static final Logger log = LoggerFactory.getLogger(WelstoryItemLookupService.class);
	private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofMinutes(10);
	private static final Duration PUBLIC_IP_CACHE_DURATION = Duration.ofMinutes(10);
	private static final ZoneId LOG_ZONE = ZoneId.of("Asia/Seoul");
	/** 모르는 사업장이 들어와도 계정별 사업장 목록은 이 간격 안에 한 번만 다시 받는다. */
	private static final Duration SOLD_TO_RELOAD_INTERVAL = Duration.ofMinutes(10);

	private final WebClient webClient;
	private final WelstoryGuidGenerator guidGenerator;
	private final String apiBaseUrl;
	private final String tokenUrl;
	private final String revokeUrl;
	private final String realtimeItemUrl;
	private final String configuredAccessToken;
	/** 웰스토리 계정(지급처)별 인증값. 첫 번째가 기본(법인). 개인 계정은 설정했을 때만 들어간다. */
	private final List<Credential> credentials;
	private final String publicIpUrl;
	private final Duration timeout;
	private final HttpClient publicIpClient;

	/** client 키 → 발급받은 토큰. 토큰은 계정마다 따로 발급·보관한다. */
	private final Map<String, CachedToken> cachedTokens = new ConcurrentHashMap<>();
	/** 사업장 코드(soldTo) → 그 사업장이 속한 client 키. 계정이 둘 이상일 때만 쓴다. */
	private final Map<String, String> soldToClients = new ConcurrentHashMap<>();
	private volatile Instant soldToClientsLoadedAt;
	private volatile CachedPublicIp cachedPublicIp;
	/** 설정의 고정 access-token이 만료 응답을 받았으면 true. 이후 client-id/secret으로 발급한 토큰을 쓴다. */
	private volatile boolean configuredTokenExpired;

	public WelstoryItemLookupService(WebClient.Builder webClientBuilder, WelstoryGuidGenerator guidGenerator,
			@Value("${welstory.api.base-url:}") String apiBaseUrl, @Value("${welstory.api.token-url:}") String tokenUrl,
			@Value("${welstory.api.revoke-url:}") String revokeUrl,
			@Value("${welstory.api.realtime-item-url:}") String realtimeItemUrl,
			@Value("${welstory.api.access-token:}") String configuredAccessToken,
			@Value("${welstory.api.client-id:}") String clientId,
			@Value("${welstory.api.client-secret:}") String clientSecret,
			@Value("${welstory.api.personal.client-id:}") String personalClientId,
			@Value("${welstory.api.personal.client-secret:}") String personalClientSecret,
			@Value("${welstory.logging.public-ip-url:https://api.ipify.org}") String publicIpUrl,
			@Value("${welstory.api.timeout-seconds:10}") long timeoutSeconds) {
		this.webClient = webClientBuilder.build();
		this.guidGenerator = guidGenerator;
		this.apiBaseUrl = apiBaseUrl.trim();
		this.tokenUrl = tokenUrl.trim();
		this.revokeUrl = revokeUrl.trim();
		this.realtimeItemUrl = realtimeItemUrl.trim();
		this.configuredAccessToken = configuredAccessToken.trim();
		List<Credential> list = new ArrayList<>();
		list.add(new Credential(DEFAULT_CLIENT, "법인", clientId.trim(), clientSecret.trim()));
		if (!personalClientId.isBlank() || !personalClientSecret.isBlank())
			list.add(new Credential(PERSONAL_CLIENT, "개인", personalClientId.trim(), personalClientSecret.trim()));
		this.credentials = List.copyOf(list);
		this.publicIpUrl = publicIpUrl.trim();
		this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
		this.publicIpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	}

	public static final String DEFAULT_CLIENT = "corp";
	public static final String PERSONAL_CLIENT = "personal";

	public JsonNode lookup(JsonNode request) {
		return call("/fdapi/service/payer-realtime-item", request);
	}

	public JsonNode lookupSoldTo(JsonNode request) {
		return call("/fdapi/service/payer-rep-soldto", request);
	}

	/** 설정된 웰스토리 계정 키 목록(기본 계정이 먼저). 로컬·테스트는 보통 기본 하나뿐이다. */
	public List<String> clientKeys() {
		return credentials.stream().map(Credential::key).toList();
	}

	public String accessToken() {
		return accessToken(DEFAULT_CLIENT);
	}

	public String accessToken(String clientKey) {
		Credential credential = credential(clientKey);
		if (credential.isDefault() && !configuredAccessToken.isBlank() && !configuredTokenExpired)
			return configuredAccessToken;
		ensureTokenConfiguration(credential);

		CachedToken current = cachedTokens.get(credential.key());
		if (current != null && current.isUsable())
			return current.value();
		return refreshAccessToken(credential);
	}

	/** 요청의 사업장 코드(dataHeader 또는 dataBody의 soldTo)로 계정을 골라 호출한다. 사업장이 없는 요청은 기본 계정. */
	public JsonNode call(String apiPath, JsonNode request) {
		return callWith(clientFor(soldTo(request)), apiPath, request);
	}

	/** 계정을 직접 지정해 호출한다(사업장 목록처럼 계정 전체를 조회할 때). */
	public JsonNode callWith(String clientKey, String apiPath, JsonNode request) {
		Credential credential = credential(clientKey);
		ensureConfigured(apiPath, credential);
		String token = accessToken(credential.key());
		try {
			return callOnce(apiPath, request, token);
		} catch (WebClientResponseException exception) {
			if (!isTokenRejected(exception))
				throw exception;
			if (credential.isDefault() && token.equals(configuredAccessToken)) {
				// 고정 토큰 만료: client-id/secret이 있으면 새로 발급해 계속 진행, 없으면 설정 갱신을 안내한다.
				if (tokenUrl.isBlank() || credential.clientId().isBlank() || credential.clientSecret().isBlank())
					throw new IllegalStateException("웰스토리 access-token이 만료되었습니다. 설정의 welstory.api.access-token을 갱신하거나 client-id/client-secret을 설정해주세요.");
				configuredTokenExpired = true;
			}
			invalidate(credential.key(), token);
			return callOnce(apiPath, request, accessToken(credential.key()));
		}
	}

	/** 사업장 목록을 계정별로 받았을 때 알려 준다. 이후 그 사업장 호출은 해당 계정으로 나간다. */
	public void rememberSoldTos(String clientKey, Collection<String> soldTos) {
		String key = credential(clientKey).key();
		for (String soldTo : soldTos)
			if (soldTo != null && !soldTo.isBlank())
				soldToClients.put(soldTo.trim(), key);
	}

	/** 사업장이 속한 계정 키. 계정이 하나면 항상 기본, 모르는 사업장이면 계정별 사업장 목록을 받아 확인한다. */
	String clientFor(String soldTo) {
		if (credentials.size() == 1 || soldTo == null || soldTo.isBlank())
			return DEFAULT_CLIENT;
		String key = soldToClients.get(soldTo);
		if (key == null) {
			loadSoldToClients();
			key = soldToClients.get(soldTo);
		}
		if (key == null) {
			log.warn("웰스토리 사업장 {}이 어느 계정에도 없어 기본(법인) 계정으로 호출합니다.", soldTo);
			return DEFAULT_CLIENT;
		}
		return key;
	}

	private synchronized void loadSoldToClients() {
		Instant loadedAt = soldToClientsLoadedAt;
		if (loadedAt != null && Instant.now().isBefore(loadedAt.plus(SOLD_TO_RELOAD_INTERVAL)))
			return;
		for (Credential credential : credentials) {
			try {
				rememberSoldTos(credential.key(), soldTosOf(credential.key()));
			} catch (RuntimeException exception) {
				log.warn("웰스토리 {} 계정 사업장 목록 조회 실패: {}", credential.label(), exception.getMessage());
			}
		}
		soldToClientsLoadedAt = Instant.now();
	}

	/** 한 계정의 전체 사업장 코드(payer-rep-soldto, 페이지 끝까지). */
	private List<String> soldTosOf(String clientKey) {
		List<String> codes = new ArrayList<>();
		String contYn = "Y", nextKey = "";
		boolean retried = false;
		for (int page = 0; page < 1000; page++) {
			ObjectNode request = JsonNodeFactory.instance.objectNode();
			request.putObject("dataHeader").put("pageRow", 100).put("contYn", contYn).put("nextKey", nextKey);
			request.putObject("dataBody");
			JsonNode response = callWith(clientKey, "/fdapi/service/payer-rep-soldto", request);
			JsonNode body = response == null ? JsonNodeFactory.instance.objectNode() : response.path("dataBody");
			String code = body.path("resCd").asText("");
			if (!code.isBlank() && !"S0000".equals(code)) {
				// 웰스토리는 첫 조회를 contYn=Y로 하면 '조회 데이터가 존재하지 않습니다'를 줄 때가 있어 N으로 한 번 더 묻는다
				if (!retried && page == 0 && body.path("resMsg").asText("").contains("조회 데이터가 존재하지 않습니다")) {
					contYn = "N";
					retried = true;
					page = -1;
					continue;
				}
				throw new IllegalStateException(body.path("resMsg").asText(code));
			}
			JsonNode data = body.path("data");
			if (data.isArray())
				data.forEach(row -> {
					String soldTo = row.path("soldTo").asText(row.path("solTo").asText("")).trim();
					if (!soldTo.isEmpty())
						codes.add(soldTo);
				});
			contYn = response.path("dataHeader").path("contYn").asText("N");
			nextKey = response.path("dataHeader").path("nextKey").asText("");
			if (!"Y".equals(contYn) || nextKey.isBlank())
				return codes;
		}
		throw new IllegalStateException("웰스토리 사업장 목록 페이지 조회가 1000회를 초과했습니다.");
	}

	static String soldTo(JsonNode request) {
		if (request == null)
			return "";
		for (String part : new String[] { "dataHeader", "dataBody" }) {
			JsonNode node = request.path(part);
			String value = node.path("soldTo").asText(node.path("solTo").asText("")).trim();
			if (!value.isEmpty())
				return value;
		}
		return "";
	}

	private Credential credential(String clientKey) {
		String key = clientKey == null || clientKey.isBlank() ? DEFAULT_CLIENT : clientKey;
		return credentials.stream().filter(c -> c.key().equals(key)).findFirst()
				.orElseThrow(() -> new IllegalStateException("웰스토리 " + key + " 계정 설정(client-id/client-secret)이 없습니다."));
	}

	/**
	 * 토큰 거부 응답. 401 외에 웰스토리 게이트웨이는 만료 토큰을 HTTP 500 + {"resCd":"EGW00123","resMsg":"기간이 만료된 token 입니다."}로 돌려준다.
	 */
	static boolean isTokenRejected(WebClientResponseException exception) {
		if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED)
			return true;
		String body = exception.getResponseBodyAsString();
		return body.contains("EGW00123") || body.contains("만료된 token");
	}

	/** 기본(법인) 계정의 토큰을 폐기한다. 고정 access-token이 있으면 그것, 없으면 발급받아 둔 토큰. */
	public JsonNode revokeConfiguredToken() {
		Credential credential = credential(DEFAULT_CLIENT);
		CachedToken cached = cachedTokens.get(credential.key());
		String token = configuredAccessToken.isBlank() ? cached == null ? "" : cached.value() : configuredAccessToken;
		if (token.isBlank())
			throw new IllegalStateException("폐기할 웰스토리 access token이 없습니다.");
		if (revokeUrl.isBlank() || credential.clientId().isBlank() || credential.clientSecret().isBlank()) {
			throw new IllegalStateException("토큰 폐기를 위한 revoke-url, client-id, client-secret 설정이 필요합니다.");
		}

		Map<String, String> form = new LinkedHashMap<>();
		form.put("client_id", credential.clientId());
		form.put("client_secret", credential.clientSecret());
		form.put("token", token);
		logRequest("TOKEN_REVOKE", revokeUrl, MediaType.APPLICATION_FORM_URLENCODED_VALUE, form);
		long startedAt = System.nanoTime();
		Mono<JsonNode> responseMono = webClient.post().uri(revokeUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.accept(MediaType.APPLICATION_JSON)
				.body(BodyInserters.fromFormData("client_id", credential.clientId())
						.with("client_secret", credential.clientSecret()).with("token", token))
				.exchangeToMono(
						clientResponse -> handleResponse("TOKEN_REVOKE", revokeUrl, "-", startedAt, clientResponse));
		JsonNode response = blockResponse(responseMono, "TOKEN_REVOKE", revokeUrl, "-", startedAt);
		invalidate(credential.key(), token);
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
		Mono<JsonNode> responseMono = webClient.post().uri(url).contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("guid", guid).bodyValue(request)
				.exchangeToMono(response -> handleResponse("API", url, guid, startedAt, response));
		return blockResponse(responseMono, "API", url, guid, startedAt);
	}

	private synchronized String refreshAccessToken(Credential credential) {
		CachedToken current = cachedTokens.get(credential.key());
		if (current != null && current.isUsable())
			return current.value();

		Map<String, String> form = new LinkedHashMap<>();
		form.put("client_id", credential.clientId());
		form.put("client_secret", credential.clientSecret());
		form.put("scope", "oob");
		form.put("grant_type", "client_credentials");
		logRequest("TOKEN_ISSUE", tokenUrl, MediaType.APPLICATION_FORM_URLENCODED_VALUE, form);
		long startedAt = System.nanoTime();
		Mono<JsonNode> responseMono = webClient.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.accept(MediaType.APPLICATION_JSON)
				.body(BodyInserters.fromFormData("client_id", credential.clientId())
						.with("client_secret", credential.clientSecret()).with("scope", "oob")
						.with("grant_type", "client_credentials"))
				.exchangeToMono(
						clientResponse -> handleResponse("TOKEN_ISSUE", tokenUrl, "-", startedAt, clientResponse));
		JsonNode response = blockResponse(responseMono, "TOKEN_ISSUE", tokenUrl, "-", startedAt);

		String token = response == null ? "" : response.path("access_token").asText("").trim();
		if (token.isEmpty()) {
			String description = response == null ? "" : response.path("error_description").asText("");
			throw new IllegalStateException(description.isBlank() ? "웰스토리 access token 발급에 실패했습니다." : description);
		}
		long expiresIn = Math.max(1, response.path("expires_in").asLong(3600));
		cachedTokens.put(credential.key(), new CachedToken(token, Instant.now().plusSeconds(expiresIn)));
		return token;
	}

	private void invalidate(String clientKey, String token) {
		cachedTokens.computeIfPresent(clientKey, (key, cached) -> cached.value().equals(token) ? null : cached);
	}

	private void ensureConfigured(String apiPath, Credential credential) {
		boolean realtimeItemApi = "/fdapi/service/payer-realtime-item".equals(apiPath);
		if (apiBaseUrl.isBlank() && (!realtimeItemApi || realtimeItemUrl.isBlank())) {
			throw new IllegalStateException("welstory.api.base-url 설정이 필요합니다.");
		}
		if (!credential.isDefault() || configuredAccessToken.isBlank())
			ensureTokenConfiguration(credential);
	}

	private void ensureTokenConfiguration(Credential credential) {
		if (tokenUrl.isBlank())
			throw new IllegalStateException("welstory.api.token-url 설정이 필요합니다.");
		if (credential.clientId().isBlank() || credential.clientSecret().isBlank()) {
			throw new IllegalStateException(credential.isDefault() ? "welstory.api.client-id와 client-secret 설정이 필요합니다."
					: "welstory.api.personal.client-id와 client-secret 설정이 필요합니다.");
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
		Map<String, String> safeHeaders = new LinkedHashMap<>(headers);
		if (safeHeaders.containsKey(HttpHeaders.AUTHORIZATION)) {
			safeHeaders.put(HttpHeaders.AUTHORIZATION, "Bearer ***");
		}
		Object safeBody = sanitizeLogBody(body);
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
				===============================================""", type, OffsetDateTime.now(LOG_ZONE),
				resolveLocalIp(url), resolvePublicIp(), url, safeHeaders, safeBody, wireFormat);
	}

	private Object sanitizeLogBody(Object body) {
		if (!(body instanceof Map<?, ?> source))
			return body;
		Map<String, Object> safe = new LinkedHashMap<>();
		source.forEach((key, value) -> {
			String name = String.valueOf(key);
			if ("client_secret".equalsIgnoreCase(name) || "token".equalsIgnoreCase(name)
					|| "access_token".equalsIgnoreCase(name) || "refresh_token".equalsIgnoreCase(name)) {
				safe.put(name, "***");
			} else {
				safe.put(name, value);
			}
		});
		return safe;
	}

	private Mono<JsonNode> handleResponse(String type, String url, String guid, long startedAt,
			ClientResponse response) {
		long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
		if (response.statusCode().isError()) {
			return response.createException().flatMap(exception -> {
				logResponse(type, url, guid, exception.getStatusCode().value(), exception.getHeaders(),
						exception.getResponseBodyAsString(), elapsedMs);
				return Mono.error(exception);
			});
		}

		return response.bodyToMono(JsonNode.class)
				.doOnNext(body -> logResponse(type, url, guid, response.statusCode().value(),
						response.headers().asHttpHeaders(), body, elapsedMs))
				.switchIfEmpty(Mono.fromRunnable(() -> logResponse(type, url, guid, response.statusCode().value(),
						response.headers().asHttpHeaders(), "<empty>", elapsedMs)));
	}

	private JsonNode blockResponse(Mono<JsonNode> responseMono, String type, String url, String guid, long startedAt) {
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

	private void logCommunicationFailure(String type, String url, String guid, long startedAt,
			RuntimeException exception) {
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
				===============================================""", type, OffsetDateTime.now(LOG_ZONE), guid, url,
				elapsedMs, exception.getClass().getName(), exception.getMessage(), exception);
	}

	private void logResponse(String type, String url, String guid, int status, HttpHeaders headers, Object body,
			long elapsedMs) {
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
				===============================================""", type, OffsetDateTime.now(LOG_ZONE), guid, url,
				status, elapsedMs, headers, body);
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
		if (current != null && current.isUsable())
			return current.value();
		if (publicIpUrl.isBlank())
			return "disabled";

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(publicIpUrl)).timeout(Duration.ofSeconds(2)).GET()
					.build();
			String value = publicIpClient.send(request, HttpResponse.BodyHandlers.ofString()).body().trim();
			if (value.isBlank() || value.length() > 45)
				return "unknown (invalid response)";
			cachedPublicIp = new CachedPublicIp(value, Instant.now().plus(PUBLIC_IP_CACHE_DURATION));
			return value;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return "unknown (" + exception.getClass().getSimpleName() + ")";
		} catch (Exception exception) {
			return "unknown (" + exception.getClass().getSimpleName() + ")";
		}
	}

	private record Credential(String key, String label, String clientId, String clientSecret) {
		boolean isDefault() {
			return DEFAULT_CLIENT.equals(key);
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
