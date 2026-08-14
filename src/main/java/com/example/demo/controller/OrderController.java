package com.example.demo.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.demo.service.OrderService;
import com.example.demo.service.WelstoryItemLookupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;

@RestController
public class OrderController {
	private static final String DEFAULT_SOLD_TO = "A0199183";

	private final OrderService orderService;
	private final WelstoryItemLookupService welstoryItemLookupService;
	private final ObjectMapper objectMapper;
	private final String uploadDir;

	@Autowired
	public OrderController(
			OrderService orderService,
			WelstoryItemLookupService welstoryItemLookupService,
			ObjectMapper objectMapper,
			@Value("${file.upload-dir}") String uploadDir) {
		this.orderService = orderService;
		this.welstoryItemLookupService = welstoryItemLookupService;
		this.objectMapper = objectMapper;
		this.uploadDir = uploadDir;
	}

	/*
	 * method : MenuList
	 * comment : 메뉴 조회
	 */
	@GetMapping("/Order/MenuList")
	public String MenuList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = orderService.MenuList(paramMap);

		return new Gson().toJson(resultList);
	}
	/*
	 * method : DetailList
	 * comment : 메뉴 식재료 조회
	 */
	@GetMapping("/Order/DetailList")
	public String DetailList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = orderService.DetailList(paramMap);

		return new Gson().toJson(resultList);
	}

	@PostMapping(value = "/Order/ItemLookup", consumes = "application/json", produces = "application/json")
	public ResponseEntity<JsonNode> itemLookup(@RequestBody JsonNode request) {
		String validationMessage = validateItemLookupRequest(request);
		if (validationMessage != null) {
			return ResponseEntity.badRequest().body(errorBody("E4000", validationMessage));
		}

		try {
			JsonNode response = welstoryItemLookupService.lookup(withResolvedSoldTo(request));
			if (response == null) {
				return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
						.body(errorBody("E5020", "웰스토리 품목 조회 응답이 없습니다."));
			}
			return ResponseEntity.ok(response);
		} catch (WebClientResponseException e) {
			JsonNode responseBody = parseResponseBody(e.getResponseBodyAsString());
			return ResponseEntity.status(e.getStatusCode())
					.body(responseBody != null
							? responseBody
							: errorBody("E5021", "웰스토리 API 호출에 실패했습니다."));
		} catch (Exception e) {
			String message = e.getMessage() == null ? "웰스토리 품목 조회에 실패했습니다." : e.getMessage();
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorBody("E5022", message));
		}
	}

	@PostMapping(value = "/Order/workplaceLookup", consumes = "application/json", produces = "application/json")
	public ResponseEntity<JsonNode> soldToLookup(@RequestBody JsonNode request) {
		JsonNode header = request == null ? null : request.path("dataHeader");
		String validationMessage = validatePaging(header);
		if (validationMessage != null) {
			return ResponseEntity.badRequest().body(errorBody("E4000", validationMessage));
		}

		try {
			JsonNode response = welstoryItemLookupService.lookupSoldTo(request);
			return response == null
					? ResponseEntity.status(HttpStatus.BAD_GATEWAY)
							.body(errorBody("E5020", "웰스토리 사업장 조회 응답이 없습니다."))
					: ResponseEntity.ok(response);
		} catch (Exception e) {
			return welstoryFailure(e, "웰스토리 사업장 조회에 실패했습니다.");
		}
	}

	private String validateItemLookupRequest(JsonNode request) {
		JsonNode header = request == null ? null : request.path("dataHeader");
		if (header == null || header.isMissingNode() || !header.isObject()) {
			return "dataHeader가 필요합니다.";
		}

		String soldTo = requestedSoldTo(header);
		String itemCode = header.path("itemCode").asText("").trim();
		String deliveryDate = header.path("reqDeliveryDate").asText("").trim();
		if (soldTo.length() > 10) return "사업장코드는 10자리 이하여야 합니다.";
		if (itemCode.isEmpty() || itemCode.length() > 18) return "품목코드는 필수이며 18자리 이하여야 합니다.";
		if (!deliveryDate.matches("\\d{8}")) return "입고일자는 YYYYMMDD 형식이어야 합니다.";
		try {
			java.time.LocalDate.parse(deliveryDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
		} catch (java.time.format.DateTimeParseException e) {
			return "유효한 입고일자를 입력해 주세요.";
		}
		return null;
	}

	private JsonNode withResolvedSoldTo(JsonNode request) {
		ObjectNode normalizedRequest = request.deepCopy();
		ObjectNode header = (ObjectNode) normalizedRequest.path("dataHeader");
		String soldTo = requestedSoldTo(header);
		if (soldTo.isEmpty()) soldTo = DEFAULT_SOLD_TO;
		header.put("soldTo", soldTo);
		header.remove("solTo");
		return normalizedRequest;
	}

	private String requestedSoldTo(JsonNode header) {
		String soldTo = header.path("soldTo").asText("").trim();
		return soldTo.isEmpty() ? header.path("solTo").asText("").trim() : soldTo;
	}

	private String validatePaging(JsonNode header) {
		if (header == null || !header.isObject()) return "dataHeader가 필요합니다.";
		int pageRow = header.path("pageRow").asInt(0);
		if (pageRow < 1 || pageRow > 10000) return "pageRow는 1~10000 범위여야 합니다.";
		String contYn = header.path("contYn").asText("").trim();
		if (!contYn.matches("Y|N")) return "contYn은 Y 또는 N이어야 합니다.";
		if ("Y".equals(contYn) && header.path("nextKey").asText("").trim().isEmpty()) {
			return "다음 페이지 조회 시 nextKey가 필요합니다.";
		}
		return null;
	}

	private ResponseEntity<JsonNode> welstoryFailure(Exception e, String defaultMessage) {
		if (e instanceof WebClientResponseException webException) {
			JsonNode responseBody = parseResponseBody(webException.getResponseBodyAsString());
			return ResponseEntity.status(webException.getStatusCode())
					.body(responseBody != null ? responseBody : errorBody("E5021", defaultMessage));
		}
		String message = e.getMessage() == null ? defaultMessage : e.getMessage();
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorBody("E5022", message));
	}

	private JsonNode errorBody(String code, String message) {
		return objectMapper.valueToTree(Map.of(
				"dataHeader", Map.of(),
				"dataBody", Map.of("resCd", code, "resMsg", message)));
	}

	private JsonNode parseResponseBody(String body) {
		try {
			return body == null || body.isBlank() ? null : objectMapper.readTree(body);
		} catch (Exception ignored) {
			return null;
		}
	}
}
