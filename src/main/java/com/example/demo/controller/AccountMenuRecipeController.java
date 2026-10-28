package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.AccountMenuRecipeService;
import com.example.demo.service.MealRecipeRefreshService;

@RestController
@RequestMapping("/v2/menu-management")
public class AccountMenuRecipeController {
	private final AccountMenuRecipeService service;
	/** 레시피 줄을 바꾸면 그 메뉴가 든 오늘 이후·사용 확정 전 식사의 필요량을 다시 계산(2026-10-28) */
	private final MealRecipeRefreshService refresh;

	public AccountMenuRecipeController(AccountMenuRecipeService service, MealRecipeRefreshService refresh) {
		this.service = service;
		this.refresh = refresh;
	}

	/*
	 * part : 거래처 메뉴관리 method : ingredients comment : 거래처 활성 메뉴에서 사용하는 식자재 목록 조회 (미연결 식자재 포함, 연결 상품명·공급사·사용 메뉴 수)
	 */
	@GetMapping("/ingredients")
	public ResponseEntity<?> ingredients(@RequestParam Map<String, Object> p) {
		return run(() -> service.ingredients(p));
	}

	/*
	 * part : 거래처 메뉴관리 method : origins comment : 연결된 공급상품의 공급사 원문 기준 식자재 원산지 조회
	 */
	@GetMapping("/origins")
	public ResponseEntity<?> origins(@RequestParam Map<String, Object> p) {
		return run(() -> service.origins(p));
	}

	/*
	 * part : 거래처 메뉴관리 method : saveIngredient comment : 거래처 식자재 등록/수정
	 */
	@PostMapping("/ingredients")
	public ResponseEntity<?> saveIngredient(@RequestBody Map<String, Object> b) {
		return run(() -> service.saveIngredient(b));
	}

	/*
	 * part : 거래처 메뉴관리 method : deleteIngredient comment : 거래처 식자재 삭제
	 */
	@DeleteMapping("/ingredients")
	public ResponseEntity<?> deleteIngredient(@RequestBody Map<String, Object> b) {
		return run(() -> service.deleteIngredient(b));
	}

	/*
	 * part : 거래처 메뉴관리 method : menus comment : 거래처 메뉴 목록 조회
	 */
	@GetMapping("/menus")
	public ResponseEntity<?> menus(@RequestParam Map<String, Object> p) {
		return run(() -> service.menus(p));
	}

	/*
	 * part : 거래처 메뉴관리 method : saveMenu comment : 거래처 메뉴 등록/수정 (칼로리 값 검증 포함)
	 */
	@PostMapping("/menus")
	public ResponseEntity<?> saveMenu(@RequestBody Map<String, Object> b) {
		return run(() -> service.saveMenu(b));
	}

	/*
	 * part : 거래처 메뉴관리 method : deleteMenu comment : 거래처 메뉴 삭제
	 */
	@DeleteMapping("/menus")
	public ResponseEntity<?> deleteMenu(@RequestBody Map<String, Object> b) {
		return run(() -> service.deleteMenu(b));
	}

	/*
	 * part : 거래처 메뉴관리 method : register comment : 메뉴와 레시피(표준 식자재 구성)를 한 트랜잭션으로 함께 등록. 공급상품 정보는 요구하지 않음
	 */
	@PostMapping("/menus/register-with-recipe")
	public ResponseEntity<?> register(@RequestBody Map<String, Object> b) {
		return run(() -> service.registerMenu(b));
	}

	/*
	 * part : 거래처 메뉴관리 method : recipes comment : 메뉴 레시피(식자재 구성) 상세 조회
	 */
	@GetMapping("/recipes")
	public ResponseEntity<?> recipes(@RequestParam Map<String, Object> p) {
		return run(() -> service.recipes(p));
	}

	/*
	 * part : 거래처 메뉴관리 method : createRecipe comment : 메뉴 레시피에 식자재 추가
	 */
	@PostMapping("/recipes")
	public ResponseEntity<?> createRecipe(@RequestBody Map<String, Object> b) {
		return run(() -> refresh.withRefresh(service.createRecipe(b), b.get("account_id"), b.get("menu_id")));
	}

	/*
	 * part : 거래처 메뉴관리 method : updateRecipe comment : 메뉴 레시피 식자재 수정
	 */
	@PatchMapping("/recipes")
	public ResponseEntity<?> updateRecipe(@RequestBody Map<String, Object> b) {
		return run(() -> {
			Map<String, Object> owner = service.recipeOwner(b);
			Object result = service.updateRecipe(b);
			return owner == null ? result : refresh.withRefresh(result, owner.get("account_id"), owner.get("menu_id"));
		});
	}

	/*
	 * part : 거래처 메뉴관리 method : deleteRecipe comment : 메뉴 레시피 식자재 삭제
	 */
	@DeleteMapping("/recipes")
	public ResponseEntity<?> deleteRecipe(@RequestBody Map<String, Object> b) {
		return run(() -> {
			// 지우기 전에 어느 메뉴 줄인지 읽어 둔다
			Map<String, Object> owner = service.recipeOwner(b);
			Object result = service.deleteRecipe(b);
			return owner == null ? result : refresh.withRefresh(result, owner.get("account_id"), owner.get("menu_id"));
		});
	}

	private ResponseEntity<?> run(Supplier<?> a) {
		try {
			return ResponseEntity.ok(a.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
