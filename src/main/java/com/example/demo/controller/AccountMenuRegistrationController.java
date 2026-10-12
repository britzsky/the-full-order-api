package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.AccountMenuRegistrationService;

@RestController
@RequestMapping("/v2/menu-management")
public class AccountMenuRegistrationController {
	private final AccountMenuRegistrationService service;

	public AccountMenuRegistrationController(AccountMenuRegistrationService service) {
		this.service = service;
	}

	/*
	 * part : 거래처 메뉴관리 method : registerNew comment : 거래처 전용 메뉴 새로 등록(식단표 PDF 미등록 메뉴 등). 칼로리·메뉴유형·메뉴구분·식재료 1개 이상 필수, menu_id는 서버가 AM-로 발급
	 */
	@PostMapping("/menus/register-new")
	public ResponseEntity<?> registerNew(@RequestBody Map<String, Object> b) {
		return run(() -> service.register(b));
	}

	/*
	 * part : 거래처 메뉴관리 method : importStandard comment : 본사 메뉴를 거래처 메뉴로 가져오기(거래처 레시피가 없을 때만 본사 레시피·칼로리 복사)
	 */
	@PostMapping("/menus/import-standard")
	public ResponseEntity<?> importStandard(@RequestBody Map<String, Object> b) {
		return run(() -> service.importStandard(b));
	}

	/*
	 * part : 거래처 메뉴관리 method : promote comment : 거래처 전용 메뉴를 같은 menu_id로 본사 메뉴·표준 레시피에 반영(사용자가 '예'를 고른 경우만)
	 */
	@PostMapping("/menus/promote")
	public ResponseEntity<?> promote(@RequestBody Map<String, Object> b) {
		return run(() -> service.promote(b));
	}

	/*
	 * part : 거래처 메뉴관리 method : promotePreview comment : 본사 반영 전 식재료별 처리 미리보기(SAME 같은 ID·MATCHED 이름·단위가 같은 본사 식자재·NEW 본사 식자재로 추가)와 반영 불가 사유
	 */
	@GetMapping("/menus/promote-preview")
	public ResponseEntity<?> promotePreview(@RequestParam Map<String, Object> p) {
		return run(() -> service.promotePreview(p));
	}

	/*
	 * part : 거래처 메뉴관리 method : accountIngredients comment : 메뉴 등록 창 식재료 목록(거래처 식재료, 사용 중인 것)
	 */
	@GetMapping("/ingredients/account")
	public ResponseEntity<?> accountIngredients(@RequestParam Map<String, Object> p) {
		return run(() -> service.ingredients(p));
	}

	/*
	 * part : 거래처 메뉴관리 method : registerIngredient comment : 거래처 식재료 직접 추가(본사 식자재 없이, ID AI-…). 기준단위 g/ml/EA, 같은 이름 거부
	 */
	@PostMapping("/ingredients/register-new")
	public ResponseEntity<?> registerIngredient(@RequestBody Map<String, Object> b) {
		return run(() -> service.registerIngredient(b));
	}

	private ResponseEntity<?> run(Supplier<?> a) {
		try {
			return ResponseEntity.ok(a.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
