package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 웰스토리 품질(stg) 서버의 상황 조회(payer-situation-detail, 읽기 전용)를 실제로 호출해 응답 모양을 확인한다.
 * 운영 웰스토리 주소면 멈춘다. 사업장 코드는 로컬 DB에서 읽기만 한다. DB에 쓰지 않는다. 실행: RUN_WELSTORY_LIVE=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_WELSTORY_LIVE", matches = "true")
class LiveWelstorySituationTest {
	@Test
	void situationDetailOnStaging() throws Exception {
		var root = Path.of("src/main/resources");
		var props = new Properties();
		try (var r = Files.newBufferedReader(root.resolve("application.properties_local"))) { props.load(r); }
		try (var r = Files.newBufferedReader(root.resolve("application-secret.properties"))) { props.load(r); }
		String base = props.getProperty("welstory.api.base-url", "");
		assertThat(base).contains("-stg-");
		String url = props.getProperty("spring.datasource.url");
		assertThat(url).startsWith("jdbc:mysql://localhost:3306/");
		List<String> sites = new ArrayList<>();
		try (var c = DriverManager.getConnection(url, props.getProperty("spring.datasource.username"), props.getProperty("spring.datasource.password"));
				var rs = c.createStatement().executeQuery("SELECT DISTINCT ss.external_site_code FROM tb_supplier_account_site ss JOIN tb_supplier s"
						+ " ON s.supplier_id=ss.supplier_id WHERE s.supplier_code='WELSTORY' ORDER BY 1")) {
			while (rs.next()) sites.add(rs.getString(1));
		}
		System.out.println("WELSTORY sites in local DB: " + sites);
		var client = new WelstoryItemLookupService(WebClient.builder(), new WelstoryGuidGenerator(), base,
				props.getProperty("welstory.api.token-url", ""), props.getProperty("welstory.api.revoke-url", ""),
				props.getProperty("welstory.api.realtime-item-url", ""), props.getProperty("welstory.api.access-token", ""),
				props.getProperty("welstory.api.client-id", ""), props.getProperty("welstory.api.client-secret", ""), "", "", "", 20);
		var f = DateTimeFormatter.BASIC_ISO_DATE;
		LocalDate to = LocalDate.now(), from = to.minusDays(Long.parseLong(System.getenv().getOrDefault("WELSTORY_DAYS", "30")));
		var json = new ObjectMapper();
		for (String site : sites) {
			var request = json.createObjectNode();
			request.putObject("dataHeader").put("soldTo", site).put("deliDateFrom", System.getenv().containsKey("WELSTORY_BY_CREATED") ? "" : from.format(f)).put("deliDateTo", System.getenv().containsKey("WELSTORY_BY_CREATED") ? "" : to.format(f))
					.put("crDateFrom", System.getenv().containsKey("WELSTORY_BY_CREATED") ? from.format(f) : "").put("crDateTo", System.getenv().containsKey("WELSTORY_BY_CREATED") ? to.format(f) : "").put("clientOrd", "");
			request.putObject("dataBody");
			JsonNode body = client.call("/fdapi/service/payer-situation-detail", request).path("dataBody");
			Map<String, Integer> types = new TreeMap<>();
			body.path("data").forEach(row -> types.merge(row.path("csType").asText("?") + ":" + row.path("orderType").asText(""), 1, Integer::sum));
			System.out.println(site + " resCd=" + body.path("resCd").asText() + " msg=" + body.path("resMsg").asText() + " rows=" + body.path("data").size() + " types=" + types);
			if (body.path("data").size() > 0) {
				var first = body.path("data").get(0);
				System.out.println("  fields=" + iterable(first.fieldNames()));
				System.out.println("  sample csType=" + first.path("csType").asText() + " clientOrd=" + first.path("clientOrd").asText()
						+ " clientOrdItem=" + first.path("clientOrdItem").asText() + " csQty=" + first.path("csQty").asText() + " ndeliDate=" + first.path("ndeliDate").asText());
			}
		}
	}

	static List<String> iterable(Iterator<String> it) {
		List<String> list = new ArrayList<>();
		it.forEachRemaining(list::add);
		return list;
	}
}
