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
import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/Procurement")
public class ProcurementController {

	private final ProcurementService service;

	public ProcurementController(ProcurementService service) {
		this.service = service;
	}

	@GetMapping("/MealPlanAnalysis")
	public ResponseEntity<?> mealPlanAnalysis(@RequestParam Map<String, Object> params) {
		return execute(() -> service.analyze(params));
	}

	@GetMapping("/Orders")
	public ResponseEntity<?> orders(@RequestParam Map<String, Object> params) {
		return execute(() -> service.list(params));
	}

	@PostMapping("/Order")
	public ResponseEntity<?> order(@RequestBody JsonNode payload) {
		return execute(() -> service.order(payload));
	}

	@PostMapping("/Reconcile")
	public ResponseEntity<?> reconcile(@RequestBody Map<String, Object> params) {
		return execute(() -> service.reconcile(params));
	}

	@PostMapping("/Receive")
	public ResponseEntity<?> receive(@RequestBody JsonNode payload) {
		return execute(() -> service.receive(payload));
	}

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
