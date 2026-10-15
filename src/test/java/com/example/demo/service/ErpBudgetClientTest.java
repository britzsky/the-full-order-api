package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class ErpBudgetClientTest {
	final ErpBudgetClient client = new ErpBudgetClient(WebClient.builder(), new ObjectMapper(), "https://example.invalid/api", 1);

	@Test
	void mapsUserDefinedFieldsFromErpResponse() {
		// 2026-10-15 사용자가 준 실제 응답 일부
		String body = """
				[{"use_ratio":55.98,"note":"60만원 추가 부여","budget_grant":22950939,"year":2026,"supplies_used":0,
				  "budget_total":22350939,"account_name":"사랑의","day_budget":11105293,"food_budget":223300,
				  "supplies_remaining":745031,"supplies_budget":745031,"account_id":"20250819193632","month":10,"day_use_amount":12848378}]""";
		var result = client.parse(body, "20250819193632");
		assertThat(result).containsEntry("status", "OK")
				.containsEntry("food_budget", 22950939L).containsEntry("food_used", 12848378L).containsEntry("food_remaining", 10102561L)
				.containsEntry("supplies_budget", 745031L).containsEntry("supplies_used", 0L).containsEntry("supplies_remaining", 745031L);
	}

	@Test
	void otherAccountRowOrEmptyOrBrokenBodyIsNotShown() {
		assertThat(client.parse("[{\"account_id\":\"X\",\"budget_grant\":100}]", "A")).containsEntry("status", "NONE");
		assertThat(client.parse("[]", "A")).containsEntry("status", "NONE");
		assertThat(client.parse("<html>", "A")).containsEntry("status", "ERROR");
		assertThat(client.monthly("A", 2026, 10)).containsEntry("status", "ERROR");
	}
}
