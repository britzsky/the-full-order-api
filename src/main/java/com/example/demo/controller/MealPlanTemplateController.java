package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.MealPlanTemplateService;

/** 본사 금액대 식단표 템플릿. 조회·거래처 금액대 템플릿은 현장도, 저장·삭제는 본사만(WorkspaceAccess). */
@RestController
@RequestMapping("/v2/meal-plan-templates")
public class MealPlanTemplateController {
	private final MealPlanTemplateService service;

	public MealPlanTemplateController(MealPlanTemplateService service) {
		this.service = service;
	}

	/*
	 * part : 식단표 템플릿 method : list comment : 템플릿 목록(금액대·식단유형 필터)
	 */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam Map<String, Object> p) { return run(() -> service.list(p)); }

	/*
	 * part : 식단표 템플릿 method : detail comment : 템플릿과 요일·식사별 본사 메뉴
	 */
	@GetMapping("/detail")
	public ResponseEntity<?> detail(@RequestParam Map<String, Object> p) { return run(() -> service.detail(p)); }

	/*
	 * part : 식단표 템플릿 method : forAccount comment : 거래처 1일 식단가와 같은 금액대(1,000원 단위 올림)의 템플릿
	 */
	@GetMapping("/for-account")
	public ResponseEntity<?> forAccount(@RequestParam Map<String, Object> p) { return run(() -> service.forAccount(p)); }

	/*
	 * part : 식단표 템플릿 method : save comment : 템플릿 저장(본사). 금액대·식단유형별 순번 1~3
	 */
	@PostMapping
	public ResponseEntity<?> save(@RequestBody Map<String, Object> b) { return run(() -> service.save(b)); }

	/*
	 * part : 식단표 템플릿 method : delete comment : 템플릿 삭제(본사)
	 */
	@DeleteMapping
	public ResponseEntity<?> delete(@RequestBody Map<String, Object> b) { return run(() -> service.delete(b)); }

	private ResponseEntity<?> run(Supplier<?> action) {
		try {
			return ResponseEntity.ok(action.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
