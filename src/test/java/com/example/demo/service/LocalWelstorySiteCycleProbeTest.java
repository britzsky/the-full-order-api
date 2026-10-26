package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 테스트할 웰스토리 사업장 찾기(조회 전용, DB 쓰기 없음): 사업장 목록 API(payer-rep-soldto)로 받은 사업장 코드마다
 * 전체 판가 API(payer-allitem-price)를 순기별로 1행씩 물어 응답이 있는 사업장·순기만 찍는다.
 * WELSTORY_PROBE_YEARS(기본 2024,2025,2026), WELSTORY_PROBE_FROM~TO(기본 1~18) 순기.
 * Gradle은 환경변수만 바꾸면 테스트를 건너뛰니 cleanTest를 같이 줄 것.
 */
@EnabledIfEnvironmentVariable(named = "RUN_WELSTORY_SITE_PROBE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LocalWelstorySiteCycleProbeTest {
	@Autowired WelstoryItemLookupService api;
	private final ObjectMapper json = new ObjectMapper();

	@Test void probe() {
		var env = System.getenv();
		String[] years = env.getOrDefault("WELSTORY_PROBE_YEARS", "2024,2025,2026").split(",");
		int from = Integer.parseInt(env.getOrDefault("WELSTORY_PROBE_FROM", "1"));
		int to = Integer.parseInt(env.getOrDefault("WELSTORY_PROBE_TO", "18"));
		List<JsonNode> sites = sites();
		System.out.println("WS_SITES " + sites.size());
		int found = 0;
		for (JsonNode site : sites) {
			String code = site.path("soldTo").asText("");
			List<String> hits = new ArrayList<>();
			for (String year : years)
				for (int g = from; g <= to; g++) {
					String group = String.format("%02d", g);
					ObjectNode request = json.createObjectNode();
					request.putObject("dataHeader").put("pageRow", 1).put("contYn", "N").put("nextKey", "");
					request.putObject("dataBody").put("soldTo", code).put("periodGroupYear", year.trim()).put("periodGroup", group);
					try {
						JsonNode response = api.call("/fdapi/service/payer-allitem-price", request);
						String res = response.path("dataBody").path("resCd").asText("");
						JsonNode row = response.path("dataBody").path("data").path(0);
						if ((res.isBlank() || "S0000".equals(res)) && !row.isMissingNode())
							hits.add(year.trim() + "-" + group + "(" + row.path("periodFromDt").asText() + "~" + row.path("periodToDt").asText()
									+ ", " + response.path("dataHeader").path("totalRow").asText() + "건)");
						else if (!"E1103".equals(res))
							hits.add(year.trim() + "-" + group + "=" + res + " " + response.path("dataBody").path("resMsg").asText());
					} catch (RuntimeException e) {
						hits.add(year.trim() + "-" + group + "=ERR " + e.getMessage());
					}
				}
			if (!hits.isEmpty()) {
				found++;
				System.out.println("WS_HIT " + code + " | " + site.path("soldToNm").asText() + " | " + String.join(", ", hits));
			}
		}
		System.out.println("WS_DONE sites=" + sites.size() + " hit=" + found);
	}

	private List<JsonNode> sites() {
		List<JsonNode> rows = new ArrayList<>();
		String contYn = "N", nextKey = "";
		for (int page = 0; page < 100; page++) {
			ObjectNode request = json.createObjectNode();
			request.putObject("dataHeader").put("pageRow", 1000).put("contYn", contYn).put("nextKey", nextKey);
			request.putObject("dataBody");
			JsonNode response = api.call("/fdapi/service/payer-rep-soldto", request);
			response.path("dataBody").path("data").forEach(rows::add);
			contYn = response.path("dataHeader").path("contYn").asText("N");
			nextKey = response.path("dataHeader").path("nextKey").asText("");
			if (!"Y".equals(contYn) || nextKey.isBlank()) break;
		}
		return rows;
	}
}
