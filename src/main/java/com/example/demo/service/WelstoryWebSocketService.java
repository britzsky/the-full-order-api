package com.example.demo.service;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Service
public class WelstoryWebSocketService {

	private static final Logger log = LoggerFactory.getLogger(WelstoryWebSocketService.class);

	private final boolean enabled;
	private final String websocketUrl;
	private final WelstoryItemLookupService apiService;
	private final ObjectMapper objectMapper;
	private final ApplicationEventPublisher eventPublisher;
	private final AtomicBoolean connected = new AtomicBoolean(false);
	private volatile String lastError = "";

	public WelstoryWebSocketService(
			@Value("${welstory.websocket.enabled:false}") boolean enabled,
			@Value("${welstory.websocket.url:}") String websocketUrl,
			WelstoryItemLookupService apiService,
			ObjectMapper objectMapper,
			ApplicationEventPublisher eventPublisher) {
		this.enabled = enabled;
		this.websocketUrl = websocketUrl;
		this.apiService = apiService;
		this.objectMapper = objectMapper;
		this.eventPublisher = eventPublisher;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void connectWhenReady() {
		if (!enabled) return;
		if (websocketUrl.isBlank()) {
			lastError = "welstory.websocket.url 설정이 필요합니다.";
			log.warn(lastError);
			return;
		}

		connect()
				.retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
						.maxBackoff(Duration.ofMinutes(1))
						.doBeforeRetry(signal -> log.warn("웰스토리 WebSocket 재연결 시도: {}", signal.failure().getMessage())))
				.subscribe(null, error -> {
					connected.set(false);
					lastError = error.getMessage();
					log.error("웰스토리 WebSocket 연결 종료", error);
				});
	}

	private Mono<Void> connect() {
		return Mono.fromCallable(apiService::accessToken)
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(token -> new ReactorNettyWebSocketClient().execute(
						URI.create(websocketUrl),
						session -> {
							ObjectNode auth = objectMapper.createObjectNode().put("token", token);
							return session.send(Mono.just(session.textMessage(auth.toString())))
									.thenMany(session.receive()
											.flatMap(message -> handleMessage(message.getPayloadAsText())))
									.then();
						}))
				.doOnSubscribe(ignored -> log.info("웰스토리 WebSocket 연결 시도"))
				.doFinally(ignored -> connected.set(false));
	}

	private Mono<Void> handleMessage(String payload) {
		return Mono.fromCallable(() -> {
			JsonNode message = objectMapper.readTree(payload);
			String responseCode = message.path("resCd").asText("");
			if (!responseCode.isEmpty()) {
				if ("S0000".equals(responseCode)) {
					connected.set(true);
					lastError = "";
					log.info("웰스토리 WebSocket 인증 성공");
					return null;
				}
				throw new IllegalStateException(message.path("resMsg").asText("WebSocket 인증 실패"));
			}

			String msgKey = message.path("dataHeader").path("msgKey").asText("");
			JsonNode data = message.path("dataBody").path("data");
			String type = data.path("type").asText("");
			String text = data.path("msg").path("text").asText("");
			String emrSeq = data.path("msg").path("emrSeq").asText("");
			if (msgKey.isEmpty()) throw new IllegalStateException("WebSocket 알람 msgKey가 없습니다.");

			JsonNode emergencyResponse = null;
			if ("1".equals(type) || "3".equals(type)) {
				ObjectNode request = objectMapper.createObjectNode();
				request.putObject("dataHeader").put("pageRow", 10000).put("contYn", "N").put("nextKey", "");
				request.putObject("dataBody").put("emrSeq", emrSeq).put("msgKey", msgKey);
				emergencyResponse = apiService.call("/fdapi/service/payer-emr-chgitem", request);
			} else {
				ObjectNode request = objectMapper.createObjectNode();
				request.putObject("dataHeader").put("msgKey", msgKey);
				request.putObject("dataBody");
				apiService.call("/fdapi/service/payer-alarm-response", request);
			}

			eventPublisher.publishEvent(new WelstoryAlarmEvent(
					msgKey, type, text, emrSeq, message, emergencyResponse));
			return null;
		}).subscribeOn(Schedulers.boundedElastic()).then();
	}

	public boolean isEnabled() { return enabled; }
	public boolean isConnected() { return connected.get(); }
	public String getLastError() { return lastError; }
}
