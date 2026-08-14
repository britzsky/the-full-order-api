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

	public ProcurementService(ProcurementMapper mapper, WelstoryItemLookupService welstory, ObjectMapper objectMapper) {
		this.mapper = mapper;
		this.welstory = welstory;
		this.objectMapper = objectMapper;
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
		BigDecimal totalCost = ingredients.stream().map(row -> decimal(row.get("planned_cost")))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		Map<String, Object> summary = mapper.mealPlanSummary(params);
		BigDecimal servings = decimal(summary == null ? null : summary.get("total_servings"));
		BigDecimal budget = decimal(summary == null ? null : summary.get("meal_budget_per_person"));
		BigDecimal costPerPerson = servings.signum() == 0 ? BigDecimal.ZERO
				: totalCost.divide(servings, 2, java.math.RoundingMode.HALF_UP);
		Map<String, Long> statusCounts = new LinkedHashMap<>();
		for (String status : List.of("RED", "ORANGE", "YELLOW", "GREEN")) {
			statusCounts.put(status, ingredients.stream().filter(row -> status.equals(row.get("stock_status"))).count());
		}
		return Map.of("table_id", params.get("table_id"), "total_ingredient_cost", totalCost,
				"total_servings", servings, "cost_per_person", costPerPerson,
				"meal_budget_per_person", budget, "budget_exceeded", budget.signum() > 0 && costPerPerson.compareTo(budget) > 0,
				"status_counts", statusCounts, "ingredients", ingredients);
	}

	public Map<String, Object> list(Map<String, Object> params) {
		require(params, "account_id");
		List<Map<String, Object>> orders = mapper.purchaseOrders(params);
		for (Map<String, Object> order : orders) {
			order.put("items", mapper.purchaseOrderItems(order));
		}
		return Map.of("orders", orders);
	}

	@Transactional
	public Map<String, Object> order(JsonNode payload) {
		JsonNode order = payload.path("order");
		ArrayNode items = array(payload.path("items"), "items");
		String accountId = requiredText(order, "account_id");
		String soldTo = requiredText(order, "sold_to");
		String clientOrd = requiredText(order, "client_ord");
		String deliveryDate = requiredText(order, "req_delivery_date");

		List<JsonNode> realtimeResults = new ArrayList<>();
		for (JsonNode item : items) {
			ObjectNode request = objectMapper.createObjectNode();
			request.putObject("dataHeader").put("soldTo", soldTo)
					.put("itemCode", requiredText(item, "welstory_item_code")).put("reqDeliveryDate", deliveryDate);
			request.putObject("dataBody");
			JsonNode realtime = welstory.call("/fdapi/service/payer-realtime-item", request);
			assertSuccess(realtime, "실시간 품목 조회");
			String stopType = realtime.path("dataBody").path("data").path("stopType").asText("").trim();
			if (!stopType.isEmpty()) throw new IllegalStateException("주문 중지 품목입니다: " + item.path("welstory_item_code").asText() + " (" + stopType + ")");
			realtimeResults.add(realtime);
		}

		ObjectNode request = objectMapper.createObjectNode();
		ObjectNode header = request.putObject("dataHeader");
		header.put("clientOrd", clientOrd).put("soldTo", soldTo).put("reqDeliveryDate", deliveryDate)
				.put("ordStatus", "N").put("clientNote", order.path("client_note").asText(""));
		ArrayNode details = request.putObject("dataBody").putArray("ordDetail");
		int sequence = 1;
		for (JsonNode item : items) {
			details.addObject().put("clientOrd", clientOrd).put("clientOrdItem", String.valueOf(sequence++))
					.put("itemCode", requiredText(item, "welstory_item_code"))
					.put("ordQty", requiredText(item, "order_qty"))
					.put("specialNote", item.path("special_note").asText(""))
					.put("itemDeliveryDate", deliveryDate).put("ordItemStatus", "N");
		}
		JsonNode response = welstory.call("/fdapi/service/payer-soldto-order", request);
		assertSuccess(response, "주문");

		String purchaseOrderId = UUID.randomUUID().toString();
		Map<String, Object> orderRow = new LinkedHashMap<>();
		orderRow.put("purchase_order_id", purchaseOrderId);
		orderRow.put("account_id", accountId);
		orderRow.put("sold_to", soldTo);
		orderRow.put("client_ord", clientOrd);
		orderRow.put("req_delivery_date", deliveryDate);
		orderRow.put("status", "ORDERED");
		orderRow.put("welstory_res_cd", response.path("dataBody").path("resCd").asText());
		orderRow.put("welstory_res_msg", response.path("dataBody").path("resMsg").asText());
		orderRow.put("client_note", order.path("client_note").asText(""));
		orderRow.put("user_id", order.path("user_id").asText(""));
		mapper.insertPurchaseOrder(orderRow);

		JsonNode resultItems = response.path("dataBody").path("data");
		sequence = 0;
		for (JsonNode item : items) {
			JsonNode result = resultItems.isArray() && sequence < resultItems.size() ? resultItems.get(sequence) : objectMapper.createObjectNode();
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("purchase_order_id", purchaseOrderId);
			row.put("client_ord_item", String.valueOf(sequence + 1));
			row.put("ingredient_id", requiredText(item, "ingredient_id"));
			row.put("menu_id", item.path("menu_id").asText(""));
			row.put("welstory_item_code", requiredText(item, "welstory_item_code"));
			row.put("order_qty", requiredText(item, "order_qty"));
			row.put("order_unit", item.path("order_unit").asText(""));
			row.put("base_unit", requiredText(item, "base_unit"));
			row.put("base_qty_per_order_unit", item.path("base_qty_per_order_unit").asText("1"));
			row.put("item_status", "N");
			row.put("welstory_res_cd", result.path("resCd").asText(""));
			row.put("welstory_error_msg", result.path("errorMsg").asText(""));
			mapper.insertPurchaseOrderItem(row);
			sequence++;
		}
		return Map.of("purchase_order_id", purchaseOrderId, "status", "ORDERED",
				"realtime_results", realtimeResults, "welstory_order_response", response);
	}

	@Transactional
	public Map<String, Object> reconcile(Map<String, Object> params) {
		require(params, "account_id", "purchase_order_id");
		List<Map<String, Object>> orders = mapper.purchaseOrders(params);
		if (orders.isEmpty()) throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
		Map<String, Object> order = orders.get(0);
		ObjectNode request = objectMapper.createObjectNode();
		request.putObject("dataHeader").put("soldTo", text(order.get("sold_to")))
				.put("reqDeliveryDate", text(order.get("req_delivery_date"))).put("clientOrd", text(order.get("client_ord")));
		request.putObject("dataBody");
		JsonNode response = welstory.call("/fdapi/service/payer-order-list", request);
		assertSuccess(response, "주문 대사");
		Map<String, Object> update = new LinkedHashMap<>();
		update.put("purchase_order_id", params.get("purchase_order_id"));
		update.put("status", "CONFIRMED");
		update.put("welstory_res_cd", response.path("dataBody").path("resCd").asText());
		update.put("welstory_res_msg", response.path("dataBody").path("resMsg").asText());
		mapper.updatePurchaseOrderStatus(update);
		return Map.of("purchase_order_id", params.get("purchase_order_id"), "status", "CONFIRMED", "welstory_response", response);
	}

	@Transactional
	public Map<String, Object> receive(JsonNode payload) {
		String accountId = requiredText(payload, "account_id");
		String purchaseOrderId = requiredText(payload, "purchase_order_id");
		Map<String, Object> ownership = new LinkedHashMap<>();
		ownership.put("account_id", accountId);
		ownership.put("purchase_order_id", purchaseOrderId);
		if (mapper.purchaseOrders(ownership).isEmpty()) throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
		String userId = payload.path("user_id").asText("");
		ArrayNode requestedItems = array(payload.path("items"), "items");
		int received = 0;
		for (JsonNode item : requestedItems) {
			BigDecimal receivedQty = decimal(requiredText(item, "received_qty"));
			BigDecimal multiplier = decimal(item.path("base_qty_per_order_unit").asText("1"));
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("purchase_order_id", purchaseOrderId);
			row.put("client_ord_item", requiredText(item, "client_ord_item"));
			row.put("received_qty", receivedQty);
			if (mapper.markItemReceived(row) != 1) throw new IllegalStateException("이미 입고 처리된 품목입니다: " + row.get("client_ord_item"));
			row.put("account_id", accountId);
			row.put("ingredient_id", requiredText(item, "ingredient_id"));
			row.put("base_received_qty", receivedQty.multiply(multiplier));
			row.put("base_unit", requiredText(item, "base_unit"));
			row.put("movement_id", UUID.randomUUID().toString());
			row.put("movement_reference", purchaseOrderId + ":" + row.get("client_ord_item"));
			row.put("user_id", userId);
			mapper.increaseInventory(row);
			mapper.insertInventoryMovement(row);
			received++;
		}
		Map<String, Object> update = new LinkedHashMap<>();
		update.put("purchase_order_id", purchaseOrderId);
		update.put("status", "RECEIVED");
		mapper.updatePurchaseOrderStatus(update);
		return Map.of("purchase_order_id", purchaseOrderId, "status", "RECEIVED", "received_item_count", received);
	}

	@Transactional
	public Map<String, Object> markNotReceived(Map<String, Object> params) {
		require(params, "account_id", "purchase_order_id");
		if (mapper.purchaseOrders(params).isEmpty()) throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
		params.put("status", "NOT_RECEIVED");
		mapper.updatePurchaseOrderStatus(params);
		return Map.of("purchase_order_id", params.get("purchase_order_id"), "status", "NOT_RECEIVED");
	}

	private void assertSuccess(JsonNode response, String operation) {
		String code = response == null ? "" : response.path("dataBody").path("resCd").asText("");
		if (!"S0000".equals(code)) throw new IllegalStateException(operation + " 실패: "
				+ (response == null ? "응답 없음" : response.path("dataBody").path("resMsg").asText(code)));
	}

	private ArrayNode array(JsonNode node, String field) {
		if (!node.isArray() || node.isEmpty()) throw new IllegalArgumentException(field + "은 한 건 이상 필요합니다.");
		return (ArrayNode) node;
	}

	private String requiredText(JsonNode node, String field) {
		String value = node.path(field).asText("").trim();
		if (value.isEmpty()) throw new IllegalArgumentException(field + "은 필수입니다.");
		return value;
	}

	private void require(Map<String, Object> params, String... fields) {
		for (String field : fields) if (text(params.get(field)).isBlank()) throw new IllegalArgumentException(field + "은 필수입니다.");
	}

	private String text(Object value) { return value == null ? "" : value.toString(); }
	private BigDecimal decimal(Object value) { return value == null || value.toString().isBlank() ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
}
