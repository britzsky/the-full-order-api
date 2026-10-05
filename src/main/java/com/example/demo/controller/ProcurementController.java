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

import com.example.demo.service.ProcurementService;
import com.example.demo.service.OrderWorkflowService;
import com.example.demo.service.ReceiptPostingService;
import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/Procurement")
public class ProcurementController {

	private final ProcurementService service;
	private final OrderWorkflowService workflow;
	private final ReceiptPostingService receipts;

	public ProcurementController(ProcurementService service, OrderWorkflowService workflow,
			ReceiptPostingService receipts) {
		this.service = service;
		this.workflow = workflow;
		this.receipts = receipts;
	}

	/*
	 * part : 발주관리 method : mealPlanAnalysis comment : 식단 기준 식자재 필요량·재고 상태(🔴🟠🟡🟢)·예상 원가 분석
	 */
	@GetMapping("/MealPlanAnalysis")
	public ResponseEntity<?> mealPlanAnalysis(@RequestParam Map<String, Object> params) {
		return execute(() -> service.analyze(params));
	}

	/*
	 * part : 발주관리 method : orders comment : 발주서 목록 조회
	 */
	@GetMapping("/Orders")
	public ResponseEntity<?> orders(@RequestParam Map<String, Object> params) {
		return execute(() -> workflow.list(params));
	}

	/*
	 * part : 발주관리 method : order comment : 발주서 생성 및 공급사 주문 전송 (동일 요청번호 재요청 시 기존 결과 반환)
	 */
	@PostMapping("/Order")
	public ResponseEntity<?> order(@RequestBody JsonNode payload) {
		return execute(() -> workflow.order(payload));
	}

	/*
	 * part : 발주관리 method : reconcile comment : 웰스토리 주문 조회 결과로 발주서 상태 대사
	 */
	@PostMapping("/Reconcile")
	public ResponseEntity<?> reconcile(@RequestBody Map<String, Object> params) {
		return execute(() -> workflow.reconcile(params));
	}

	/*
	 * part : 발주관리 method : receive comment : 발주서 입고 처리 (재고·LOT 반영, 원산지 보존)
	 */
	@PostMapping("/Receive")
	public ResponseEntity<?> receive(@RequestBody JsonNode payload) {
		return execute(() -> receipts.receive(payload));
	}

	/*
	 * part : 발주관리 method : notReceived comment : 발주서 미입고 처리
	 */
	@PostMapping("/NotReceived")
	public ResponseEntity<?> notReceived(@RequestBody Map<String, Object> params) {
		return execute(() -> service.markNotReceived(params));
	}

	private ResponseEntity<?> execute(Action action) {
		try {
			return ResponseEntity.ok(action.run());
		} catch (IllegalArgumentException exception) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", exception.getMessage()));
		} catch (IllegalStateException exception) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body(Map.of("code", 502, "message", exception.getMessage()));
		}
	}

	@FunctionalInterface
	private interface Action {
		Map<String, Object> run();
	}
}
