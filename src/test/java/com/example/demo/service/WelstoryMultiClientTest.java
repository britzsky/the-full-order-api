package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;

/**
 * 웰스토리 계정 둘(법인·개인): 사업장 코드로 계정을 골라 그 계정 토큰으로 호출한다.
 * 토큰은 계정마다 따로 발급되고(법인 먼저 → tok-1, 개인 → tok-2), 사업장 목록은 계정별 토큰으로 받는다.
 */
class WelstoryMultiClientTest {
	private static final String BASE = "https://welstory.test";
	private final ObjectMapper json = new ObjectMapper();
	private final AtomicInteger issued = new AtomicInteger();
	/** 실제로 나간 API 호출: "경로 Bearer토큰" */
	private final List<String> calls = new ArrayList<>();

	private Mono<ClientResponse> respond(ClientRequest request) {
		String path = request.url().getPath();
		if (path.endsWith("/oauth/2.0/token"))
			return body("{\"access_token\":\"tok-" + issued.incrementAndGet() + "\",\"expires_in\":3600}");
		String token = request.headers().getFirst(HttpHeaders.AUTHORIZATION).replace("Bearer ", "");
		calls.add(path + " " + token);
		if (path.endsWith("/payer-rep-soldto")) {
			String soldTo = token.equals("tok-1") ? "CORP01" : "PERS01";
			return body("{\"dataHeader\":{\"contYn\":\"N\",\"nextKey\":\"\"},\"dataBody\":{\"resCd\":\"S0000\",\"data\":[{\"soldTo\":\""
					+ soldTo + "\"}]}}");
		}
		return body("{\"dataBody\":{\"resCd\":\"S0000\"}}");
	}

	private static Mono<ClientResponse> body(String content) {
		return Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(content).build());
	}

	private WelstoryItemLookupService service(String personalId, String personalSecret) {
		return new WelstoryItemLookupService(WebClient.builder().exchangeFunction(this::respond),
				new WelstoryGuidGenerator(), BASE, BASE + "/oauth/2.0/token", BASE + "/oauth/2.0/revoke", "", "",
				"corp-id", "corp-secret", personalId, personalSecret, "", 5);
	}

	private JsonNode headerSoldTo(String soldTo) {
		ObjectNode request = json.createObjectNode();
		request.putObject("dataHeader").put("soldTo", soldTo);
		return request;
	}

	private JsonNode bodySoldTo(String soldTo) {
		ObjectNode request = json.createObjectNode();
		request.putObject("dataHeader");
		request.putObject("dataBody").put("soldTo", soldTo);
		return request;
	}

	@Test
	void callsUseTheClientThatOwnsTheSite() {
		WelstoryItemLookupService api = service("pers-id", "pers-secret");
		assertThat(api.clientKeys()).containsExactly("corp", "personal");

		api.call("/fdapi/service/payer-order-list", headerSoldTo("PERS01"));
		api.call("/fdapi/service/payer-allitem-price", bodySoldTo("CORP01"));
		api.call("/fdapi/service/payer-order-list", headerSoldTo("UNKNOWN"));
		api.call("/fdapi/service/payer-nores-list", json.createObjectNode());

		assertThat(issued.get()).isEqualTo(2);
		assertThat(calls).containsExactly(
				"/fdapi/service/payer-rep-soldto tok-1", "/fdapi/service/payer-rep-soldto tok-2",
				"/fdapi/service/payer-order-list tok-2",
				"/fdapi/service/payer-allitem-price tok-1",
				"/fdapi/service/payer-order-list tok-1",
				"/fdapi/service/payer-nores-list tok-1");
	}

	@Test
	void sitesReportedBySyncAreUsedWithoutReloadingTheLists() {
		WelstoryItemLookupService api = service("pers-id", "pers-secret");
		api.rememberSoldTos("personal", List.of("PERS99"));

		api.call("/fdapi/service/payer-realtime-item", headerSoldTo("PERS99"));

		// 사업장 목록을 다시 받지 않고 바로 개인 계정 토큰(처음 발급이라 tok-1)으로 호출, 법인은 나중에 따로 발급
		assertThat(calls).containsExactly("/fdapi/service/payer-realtime-item tok-1");
		assertThat(api.accessToken("personal")).isEqualTo("tok-1");
		assertThat(api.accessToken("corp")).isEqualTo("tok-2");
	}

	@Test
	void singleClientNeverLooksUpSites() {
		WelstoryItemLookupService api = service("", "");
		assertThat(api.clientKeys()).containsExactly("corp");

		api.call("/fdapi/service/payer-order-list", headerSoldTo("ANY01"));

		assertThat(issued.get()).isEqualTo(1);
		assertThat(calls).containsExactly("/fdapi/service/payer-order-list tok-1");
	}
}
