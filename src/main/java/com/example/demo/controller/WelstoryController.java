package com.example.demo.controller;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.demo.service.WelstoryItemLookupService;
import com.example.demo.service.WelstoryWebSocketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/Order/Welstory")
public class WelstoryController {

	private final WelstoryItemLookupService service;
	private final ObjectMapper objectMapper;
	private final WelstoryWebSocketService webSocketService;

	public WelstoryController(WelstoryItemLookupService service, ObjectMapper objectMapper,
			WelstoryWebSocketService webSocketService) {
		this.service = service;
		this.objectMapper = objectMapper;
		this.webSocketService = webSocketService;
	}

	@GetMapping("/WebSocketStatus")
	public Map<String, Object> webSocketStatus() {
		return Map.of(
				"enabled", webSocketService.isEnabled(),
				"connected", webSocketService.isConnected(),
				"lastError", webSocketService.getLastError());
	}

	@PostMapping("/AllItemPrice")
	public ResponseEntity<JsonNode> allItemPrice(@RequestBody JsonNode request) {
		return execute("/fdapi/service/payer-allitem-price", request, validatePricePeriod(request, 10000, false));
	}

	@PostMapping("/ChangedItemPrice")
	public ResponseEntity<JsonNode> changedItemPrice(@RequestBody JsonNode request) {
		String error = validatePricePeriod(request, 10000, false);
		if (error == null) error = requireDate(body(request), "reqDate", "요청일자");
		return execute("/fdapi/service/payer-chgitem-price", request, error);
	}

	@PostMapping("/SoldToItemPrice")
	public ResponseEntity<JsonNode> soldToItemPrice(@RequestBody JsonNode request) {
		String error = validatePricePeriod(request, 2000, true);
		JsonNode itemCodes = body(request).path("itemCodes");
		if (error == null && (!itemCodes.isArray() || itemCodes.isEmpty() || itemCodes.size() > 2000)) {
			error = "itemCodes는 1~2000개의 품목코드 배열이어야 합니다.";
		}
		return execute("/fdapi/service/payer-soldto-item-price", request, error);
	}

	@PostMapping("/EmergencyChangedItem")
	public ResponseEntity<JsonNode> emergencyChangedItem(@RequestBody JsonNode request) {
		String error = validatePaging(header(request), 10000);
		if (error == null) error = requireText(body(request), "emrSeq", "긴급순번", 0);
		if (error == null) error = requireText(body(request), "msgKey", "알람메시지Key", 0);
		return execute("/fdapi/service/payer-emr-chgitem", request, error);
	}

	@PostMapping("/SoldToList")
	public ResponseEntity<JsonNode> soldToList(@RequestBody JsonNode request) {
		return execute("/fdapi/service/payer-rep-soldto", request, validatePaging(header(request), 10000));
	}

	@PostMapping("/OrderTransaction")
	public ResponseEntity<JsonNode> orderTransaction(@RequestBody JsonNode request) {
		return execute("/fdapi/service/payer-soldto-order", request, validateOrder(request));
	}

	@PostMapping("/OrderList")
	public ResponseEntity<JsonNode> orderList(@RequestBody JsonNode request) {
		JsonNode header = header(request);
		String error = requireText(header, "soldTo", "사업장코드", 10);
		if (error == null) error = requireDate(header, "reqDeliveryDate", "입고일자");
		return execute("/fdapi/service/payer-order-list", request, error);
	}

	@PostMapping("/RealtimeItem")
	public ResponseEntity<JsonNode> realtimeItem(@RequestBody JsonNode request) {
		JsonNode header = header(request);
		String error = requireText(header, "soldTo", "사업장코드", 10);
		if (error == null) error = requireText(header, "itemCode", "품목코드", 18);
		if (error == null) error = requireDate(header, "reqDeliveryDate", "입고일자");
		return execute("/fdapi/service/payer-realtime-item", request, error);
	}

	@PostMapping("/ReceiveDetail")
	public ResponseEntity<JsonNode> receiveDetail(@RequestBody JsonNode request) {
		JsonNode header = header(request);
		String error = requireText(header, "soldTo", "사업장코드", 10);
		if (error == null) error = requireDate(header, "reqDeliveryDate", "입고일자");
		return execute("/fdapi/service/payer-receive-detail", request, error);
	}

	@PostMapping("/SituationDetail")
	public ResponseEntity<JsonNode> situationDetail(@RequestBody JsonNode request) {
		JsonNode header = header(request);
		String error = requireText(header, "soldTo", "사업장코드", 10);
		if (error == null && text(header, "deliDateFrom").isEmpty() && text(header, "crDateFrom").isEmpty()) {
			error = "입고일자 From 또는 상황생성일자 From 중 하나는 필수입니다.";
		}
		for (String field : new String[] {"deliDateFrom", "deliDateTo", "crDateFrom", "crDateTo"}) {
			if (error == null && !text(header, field).isEmpty()) error = requireDate(header, field, field);
		}
		return execute("/fdapi/service/payer-situation-detail", request, error);
	}

	@PostMapping("/AlarmResponse")
	public ResponseEntity<JsonNode> alarmResponse(@RequestBody JsonNode request) {
		return execute("/fdapi/service/payer-alarm-response", request,
				requireText(header(request), "msgKey", "메시지Key", 0));
	}

	@PostMapping("/UnansweredAlarmList")
	public ResponseEntity<JsonNode> unansweredAlarmList(@RequestBody JsonNode request) {
		String type = text(header(request), "type");
		String error = type.matches("A|0|1|2|3|4") ? null : "type은 A, 0, 1, 2, 3, 4 중 하나여야 합니다.";
		return execute("/fdapi/service/payer-nores-list", request, error);
	}

	@PostMapping("/RevokeToken")
	public ResponseEntity<JsonNode> revokeToken() {
		try {
			return ResponseEntity.ok(service.revokeConfiguredToken());
		} catch (Exception e) {
			return failure(e);
		}
	}

	private ResponseEntity<JsonNode> execute(String path, JsonNode request, String validationError) {
		if (validationError != null) return ResponseEntity.badRequest().body(error("E4000", validationError));
		try {
			JsonNode result = service.call(path, request);
			return result == null
					? ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error("E5020", "웰스토리 응답이 없습니다."))
					: ResponseEntity.ok(result);
		} catch (Exception e) {
			return failure(e);
		}
	}

	private ResponseEntity<JsonNode> failure(Exception e) {
		if (e instanceof WebClientResponseException webException) {
			try {
				return ResponseEntity.status(webException.getStatusCode())
						.body(objectMapper.readTree(webException.getResponseBodyAsString()));
			} catch (Exception ignored) { }
		}
		String message = e.getMessage() == null ? "웰스토리 API 호출에 실패했습니다." : e.getMessage();
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error("E5022", message));
	}

	private String validatePricePeriod(JsonNode request, int maxPageRows, boolean soldToRequired) {
		String error = validatePaging(header(request), maxPageRows);
		JsonNode body = body(request);
		if (error == null) error = requireText(body, "periodGroupYear", "연도", 4);
		if (error == null) error = requireText(body, "periodGroup", "순기", 2);
		if (error == null && soldToRequired) error = requireText(body, "soldTo", "사업장코드", 10);
		return error;
	}

	private String validatePaging(JsonNode header, int maxPageRows) {
		if (!header.isObject()) return "dataHeader가 필요합니다.";
		int pageRow = header.path("pageRow").asInt(0);
		if (pageRow < 1 || pageRow > maxPageRows) return "pageRow는 1~" + maxPageRows + " 범위여야 합니다.";
		String contYn = text(header, "contYn");
		if (!contYn.matches("Y|N")) return "contYn은 Y 또는 N이어야 합니다.";
		if ("Y".equals(contYn) && text(header, "nextKey").isEmpty()) return "다음 페이지 조회 시 nextKey가 필요합니다.";
		return null;
	}

	private String validateOrder(JsonNode request) {
		JsonNode header = header(request);
		String error = requireText(header, "clientOrd", "주문번호", 20);
		if (error == null) error = requireText(header, "soldTo", "사업장코드", 8);
		if (error == null) error = requireDate(header, "reqDeliveryDate", "입고일자");
		String status = text(header, "ordStatus");
		if (error == null && !status.matches("N|U")) error = "ordStatus는 N 또는 U여야 합니다.";
		JsonNode details = body(request).path("ordDetail");
		if (error == null && (!details.isArray() || details.isEmpty())) error = "ordDetail은 한 건 이상 필요합니다.";
		if (error != null) return error;
		for (JsonNode detail : details) {
			if ((error = requireText(detail, "clientOrd", "상세 주문번호", 20)) != null) return error;
			if ((error = requireText(detail, "clientOrdItem", "주문 일련번호", 6)) != null) return error;
			if ((error = requireText(detail, "itemCode", "품목코드", 18)) != null) return error;
			if ((error = requireText(detail, "ordQty", "주문수량", 0)) != null) return error;
			if ((error = requireDate(detail, "itemDeliveryDate", "품목납품일")) != null) return error;
			if (!text(detail, "ordItemStatus").matches("N|U|D")) return "ordItemStatus는 N, U, D 중 하나여야 합니다.";
		}
		return null;
	}

	private JsonNode header(JsonNode request) { return request == null ? objectMapper.createObjectNode() : request.path("dataHeader"); }
	private JsonNode body(JsonNode request) { return request == null ? objectMapper.createObjectNode() : request.path("dataBody"); }
	private String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
	private String requireText(JsonNode node, String field, String label, int maxLength) {
		String value = text(node, field);
		if (value.isEmpty()) return label + "은(는) 필수입니다.";
		return maxLength > 0 && value.length() > maxLength ? label + "은(는) " + maxLength + "자리 이하여야 합니다." : null;
	}
	private String requireDate(JsonNode node, String field, String label) {
		String value = text(node, field);
		if (!value.matches("\\d{8}")) return label + "은(는) YYYYMMDD 형식이어야 합니다.";
		try { LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE); }
		catch (DateTimeParseException e) { return label + "이(가) 유효한 날짜가 아닙니다."; }
		return null;
	}
	private JsonNode error(String code, String message) {
		return objectMapper.valueToTree(Map.of("dataHeader", Map.of(), "dataBody", Map.of("resCd", code, "resMsg", message)));
	}
}
