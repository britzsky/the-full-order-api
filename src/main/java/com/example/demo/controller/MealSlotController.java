package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.MealSlotService;

/** 본사 끼니 종류 관리. 조회는 모두, 추가·수정·순서는 본사만(WorkspaceAccess). */
@RestController
@RequestMapping("/v2/meal-slots")
public class MealSlotController {
	private final MealSlotService service;

	public MealSlotController(MealSlotService service) {
		this.service = service;
	}

	/*
	 * part : 끼니 종류 method : list comment : 전체 끼니(사용 안 함 포함)·본식/간식·쓰는 거래처 수
	 */
	@GetMapping
	public ResponseEntity<?> list() { return run(service::list); }

	/*
	 * part : 끼니 종류 method : create comment : 끼니 추가(본사). body: meal_slot_name, slot_kind(MAIN/SNACK)
	 */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody Map<String, Object> b) { return run(() -> service.create(b)); }

	/*
	 * part : 끼니 종류 method : update comment : 이름·본식/간식·사용 여부 수정(본사). body: meal_slot_code, meal_slot_name, slot_kind, active_yn
	 */
	@PutMapping
	public ResponseEntity<?> update(@RequestBody Map<String, Object> b) { return run(() -> service.update(b)); }

	/*
	 * part : 끼니 종류 method : reorder comment : 기본 표시 순서(본사). body: meal_slot_codes[]
	 */
	@PostMapping("/order")
	public ResponseEntity<?> reorder(@RequestBody Map<String, Object> b) { return run(() -> service.reorder(b)); }

	private ResponseEntity<?> run(Supplier<?> action) {
		try {
			return ResponseEntity.ok(action.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
