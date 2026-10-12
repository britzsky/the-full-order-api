package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.MenuAliasService;

@RestController
@RequestMapping("/v2/menu-management/aliases")
public class MenuAliasController {
	private final MenuAliasService service;

	public MenuAliasController(MenuAliasService service) {
		this.service = service;
	}

	/*
	 * part : 거래처 메뉴관리 method : aliases comment : 거래처 메뉴 별칭(식단표 PDF 표기 → 거래처 메뉴) 조회. menu_id를 주면 그 메뉴만
	 */
	@GetMapping
	public ResponseEntity<?> aliases(@RequestParam Map<String, Object> p) {
		return run(() -> service.aliases(p));
	}

	/*
	 * part : 거래처 메뉴관리 method : save comment : 사람이 고른 PDF 메뉴 연결을 별칭으로 저장 (같은 표기는 마지막 선택으로 교체)
	 */
	@PostMapping
	public ResponseEntity<?> save(@RequestBody Map<String, Object> b) {
		return run(() -> service.save(b));
	}

	/*
	 * part : 거래처 메뉴관리 method : delete comment : 메뉴 별칭 삭제
	 */
	@DeleteMapping
	public ResponseEntity<?> delete(@RequestBody Map<String, Object> b) {
		return run(() -> service.delete(b));
	}

	private ResponseEntity<?> run(Supplier<?> a) {
		try {
			return ResponseEntity.ok(a.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
