package com.example.demo.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.demo.service.ReceivingShortageService;
import com.fasterxml.jackson.databind.JsonNode;

/** 입고 대기·결품 처리(현장·본사 공통). 입고 처리 자체는 기존 /Procurement/Receive(검수완료 뒤 클릭) 그대로. */
@RestController
@RequestMapping("/v2/receiving")
public class ReceivingController {
	private final ReceivingShortageService service;

	public ReceivingController(ReceivingShortageService service) {
		this.service = service;
	}

	/** 아직 안 들어온 품목(배송 대기·결품 확인 필요·웰스토리 미입 등록)과 최근 결품 내역 */
	@GetMapping("/pending")
	public ResponseEntity<?> pending(@RequestParam(name = "account_id") String accountId,
			@RequestParam(name = "date_to", required = false) String dateTo) {
		return execute(() -> service.pending(accountId, dateTo));
	}

	/** 결품 확정: {account_id, purchase_order_id, items:[{client_ord_item, shortage_qty}], source, reason, contact_name, user_id} */
	@PostMapping("/shortages")
	public ResponseEntity<?> register(@RequestBody JsonNode body) {
		return execute(() -> service.register(body));
	}

	/** 결품 취소: {account_id, purchase_order_id, client_ord_item, user_id} */
	@PostMapping("/shortages/cancel")
	public ResponseEntity<?> cancel(@RequestBody JsonNode body) {
		return execute(() -> service.cancel(body));
	}

	/** 웰스토리 상황 조회(읽기 전용) 불러오기: {account_id, from_date, to_date, force} */
	@PostMapping("/situations/sync")
	public ResponseEntity<?> sync(@RequestBody JsonNode body) {
		return execute(() -> service.syncSituations(body.path("account_id").asText(""), body.path("from_date").asText(""),
				body.path("to_date").asText(""), body.path("force").asBoolean(false)));
	}

	private ResponseEntity<?> execute(Action action) {
		try {
			return ResponseEntity.ok(action.run());
		} catch (IllegalArgumentException | java.time.format.DateTimeParseException exception) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", String.valueOf(exception.getMessage())));
		} catch (IllegalStateException exception) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("code", 502, "message", String.valueOf(exception.getMessage())));
		}
	}

	@FunctionalInterface
	private interface Action {
		Map<String, Object> run();
	}
}
