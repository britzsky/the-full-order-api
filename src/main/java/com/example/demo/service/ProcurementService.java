package com.example.demo.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.ProcurementMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class ProcurementService {

	private final ProcurementMapper mapper;
	private final WelstoryItemLookupService welstory;
	private final ObjectMapper objectMapper;
	private final OrderWorkflowService workflow;
	private final ReceiptPostingService receipts;

	public ProcurementService(ProcurementMapper mapper, WelstoryItemLookupService welstory, ObjectMapper objectMapper,
			OrderWorkflowService workflow, ReceiptPostingService receipts) {
		this.mapper = mapper;
		this.welstory = welstory;
		this.objectMapper = objectMapper;
		this.workflow = workflow;
		this.receipts = receipts;
	}

	public Map<String, Object> analyze(Map<String, Object> params) {
		require(params, "account_id", "table_id");
		params.putIfAbsent("average_days", 14);
		List<Map<String, Object>> ingredients = mapper.mealPlanIngredientAnalysis(params);
		for (Map<String, Object> ingredient : ingredients) {
			ingredient.put("stock_emoji", switch (text(ingredient.get("stock_status"))) {
			case "RED" -> "🔴";
			case "ORANGE" -> "🟠";
			case "YELLOW" -> "🟡";
			default -> "🟢";
			});
		}
		BigDecimal totalCost = ingredients.stream().map(row -> decimal(row.get("planned_cost"))).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		Map<String, Object> summary = mapper.mealPlanSummary(params);
		BigDecimal servings = decimal(summary == null ? null : summary.get("total_servings"));
		BigDecimal budget = decimal(summary == null ? null : summary.get("meal_budget_per_person"));
		BigDecimal costPerPerson = servings.signum() == 0 ? BigDecimal.ZERO
				: totalCost.divide(servings, 2, java.math.RoundingMode.HALF_UP);
		Map<String, Long> statusCounts = new LinkedHashMap<>();
		for (String status : List.of("RED", "ORANGE", "YELLOW", "GREEN")) {
			statusCounts.put(status,
					ingredients.stream().filter(row -> status.equals(row.get("stock_status"))).count());
		}
		return Map.of("table_id", params.get("table_id"), "total_ingredient_cost", totalCost, "total_servings",
				servings, "cost_per_person", costPerPerson, "meal_budget_per_person", budget, "budget_exceeded",
				budget.signum() > 0 && costPerPerson.compareTo(budget) > 0, "status_counts", statusCounts,
				"ingredients", ingredients);
	}

	public Map<String, Object> list(Map<String, Object> params) {
		return workflow.list(params);
	}

	// 이전 호출자도 현재 스키마의 공통 업무 서비스를 사용한다.
	public Map<String, Object> order(JsonNode payload) {
		return workflow.order(payload);
	}

	// 이전 호출자도 현재 스키마의 공통 업무 서비스를 사용한다.
	public Map<String, Object> reconcile(Map<String, Object> params) {
		return workflow.reconcile(params);
	}

	// 이전 호출자도 현재 스키마의 공통 업무 서비스를 사용한다.
	public Map<String, Object> receive(JsonNode payload) {
		return receipts.receive(payload);
	}

	public Map<String, Object> markNotReceived(Map<String, Object> params) {
		return workflow.markNotReceived(params);
	}

	private void assertSuccess(JsonNode response, String operation) {
		String code = response == null ? "" : response.path("dataBody").path("resCd").asText("");
		if (!"S0000".equals(code))
			throw new IllegalStateException(operation + " 실패: "
					+ (response == null ? "응답 없음" : response.path("dataBody").path("resMsg").asText(code)));
	}

	private ArrayNode array(JsonNode node, String field) {
		if (!node.isArray() || node.isEmpty())
			throw new IllegalArgumentException(field + "은 한 건 이상 필요합니다.");
		return (ArrayNode) node;
	}

	private String requiredText(JsonNode node, String field) {
		String value = node.path(field).asText("").trim();
		if (value.isEmpty())
			throw new IllegalArgumentException(field + "은 필수입니다.");
		return value;
	}

	private void require(Map<String, Object> params, String... fields) {
		for (String field : fields)
			if (text(params.get(field)).isBlank())
				throw new IllegalArgumentException(field + "은 필수입니다.");
	}

	private String text(Object value) {
		return value == null ? "" : value.toString();
	}

	private BigDecimal decimal(Object value) {
		return value == null || value.toString().isBlank() ? BigDecimal.ZERO : new BigDecimal(value.toString());
	}
}
