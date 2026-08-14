package com.example.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.demo.service.OrderService;
import com.example.demo.service.WelstoryItemLookupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class OrderControllerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final OrderService orderService = mock(OrderService.class);
	private final WelstoryItemLookupService welstoryService = mock(WelstoryItemLookupService.class);
	private final OrderController controller = new OrderController(orderService, welstoryService, objectMapper, "uploads");

	@Test
	void itemLookupUsesDefaultSoldToBeforeLookingUpItem() throws Exception {
		JsonNode itemResponse = objectMapper.readTree("{\"dataBody\":{\"resCd\":\"S0000\"}}");
		when(welstoryService.lookup(any())).thenReturn(itemResponse);

		ResponseEntity<JsonNode> result = controller.itemLookup(itemRequest(null));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(welstoryService, never()).lookupSoldTo(any());
		ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
		verify(welstoryService).lookup(requestCaptor.capture());
		assertThat(requestCaptor.getValue().path("dataHeader").path("soldTo").asText()).isEqualTo("A0199183");
	}

	@Test
	void itemLookupAcceptsLegacySolToWithoutCallingSoldToLookup() {
		when(welstoryService.lookup(any())).thenReturn(objectMapper.createObjectNode());
		ObjectNode request = itemRequest("A0000003");
		ObjectNode header = (ObjectNode) request.path("dataHeader");
		header.remove("soldTo");
		header.put("solTo", "A0000003");

		ResponseEntity<JsonNode> result = controller.itemLookup(request);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(welstoryService, never()).lookupSoldTo(any());
		ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
		verify(welstoryService).lookup(requestCaptor.capture());
		assertThat(requestCaptor.getValue().path("dataHeader").path("soldTo").asText()).isEqualTo("A0000003");
		assertThat(requestCaptor.getValue().path("dataHeader").has("solTo")).isFalse();
	}

	private ObjectNode itemRequest(String soldTo) {
		ObjectNode request = objectMapper.createObjectNode();
		ObjectNode header = request.putObject("dataHeader");
		header.put("itemCode", "ITEM-1");
		header.put("reqDeliveryDate", "20260812");
		if (soldTo != null) header.put("soldTo", soldTo);
		return request;
	}
}
