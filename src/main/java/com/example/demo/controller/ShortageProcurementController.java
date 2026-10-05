package com.example.demo.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.ShortageProcurementService;
import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/Procurement")
public class ShortageProcurementController {
	private final ShortageProcurementService service;

	public ShortageProcurementController(ShortageProcurementService service) {
		this.service = service;
	}

	/*
	 * part : 부족 발주 method : shortages comment : 부족 식자재 조회 (필요량 + 안전재고 - 사용가능 현재고 - 미입고량)
	 */
	@GetMapping("/Shortages")
	public ResponseEntity<?> shortages(@RequestParam Map<String, Object> p) {
		try {
			return ResponseEntity.ok(service.shortages(p));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
		}
	}

	/*
	 * part : 부족 발주 method : draftShortages comment : 부족 식자재 조회 (조건을 POST 본문으로 전달, GET과 동일 동작)
	 */
	@PostMapping("/Shortages")
	public ResponseEntity<?> draftShortages(@RequestBody Map<String, Object> p) {
		return shortages(p);
	}

	/*
	 * part : 부족 발주 method : preview comment : 선택한 부족 식자재·공급상품으로 발주 미리보기 (중복 선택·부족량 변경 검증)
	 */
	@PostMapping("/Preview")
	public ResponseEntity<?> preview(@RequestBody JsonNode p) {
		try {
			return ResponseEntity.ok(service.preview(p));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
		}
	}
}
