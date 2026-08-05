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
import com.google.gson.Gson;

@RestController
public class OrderController {

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
			JsonNode response = welstoryItemLookupService.lookup(request);
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

	private String validateItemLookupRequest(JsonNode request) {
		JsonNode header = request == null ? null : request.path("dataHeader");
		if (header == null || header.isMissingNode() || !header.isObject()) {
			return "dataHeader가 필요합니다.";
		}

		String soldTo = header.path("soldTo").asText("").trim();
		String itemCode = header.path("itemCode").asText("").trim();
		String deliveryDate = header.path("reqDeliveryDate").asText("").trim();
		if (soldTo.isEmpty() || soldTo.length() > 10) return "사업장코드는 필수이며 10자리 이하여야 합니다.";
		if (itemCode.isEmpty() || itemCode.length() > 18) return "품목코드는 필수이며 18자리 이하여야 합니다.";
		if (!deliveryDate.matches("\\d{8}")) return "입고일자는 YYYYMMDD 형식이어야 합니다.";
		try {
			java.time.LocalDate.parse(deliveryDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
		} catch (java.time.format.DateTimeParseException e) {
			return "유효한 입고일자를 입력해 주세요.";
		}
		return null;
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
