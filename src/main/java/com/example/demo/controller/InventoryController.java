package com.example.demo.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;

import com.example.demo.WebConfig;
import com.example.demo.service.InventoryService;
import com.google.gson.Gson;

@RestController
public class InventoryController {

	private final InventoryService inventoryService;

	@Autowired
	public InventoryController(InventoryService inventoryService, WebConfig webConfig,
			@Value("${file.upload-dir}") String uploadDir) {
		this.inventoryService = inventoryService;
	}

	/*
	 * part : 재고관리 method : AccountInventoryList comment : 거래처 재고 조회
	 */
	@GetMapping("/Inventory/AccountInventoryList")
	public String AccountList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		// int iAccountType = Integer.parseInt(paramMap.get("account_type").toString());
		resultList = inventoryService.AccountInventoryList(paramMap);

		return new Gson().toJson(resultList);
	}

	/*
	 * part : 재고관리 method : list comment : 거래처 재고 목록 조회 (공통 부족량 계산 결과 포함)
	 */
	@GetMapping("/v2/inventory")
	public ResponseEntity<?> list(@RequestParam Map<String, Object> p) {
		return execute(() -> inventoryService.AccountInventoryList(p));
	}

	/*
	 * part : 재고관리 method : create comment : 거래처 재고 항목 등록
	 */
	@PostMapping("/v2/inventory")
	public ResponseEntity<?> create(@RequestBody Map<String, Object> b) {
		return execute(() -> inventoryService.create(b));
	}

	/*
	 * part : 재고관리 method : update comment : 거래처 재고 항목 수정
	 */
	@PatchMapping("/v2/inventory")
	public ResponseEntity<?> update(@RequestBody Map<String, Object> b) {
		return execute(() -> inventoryService.update(b));
	}

	/*
	 * part : 재고관리 method : delete comment : 거래처 재고 항목 삭제
	 */
	@DeleteMapping("/v2/inventory")
	public ResponseEntity<?> delete(@RequestBody Map<String, Object> b) {
		return execute(() -> inventoryService.delete(b));
	}

	/*
	 * part : 재고관리 method : movements comment : 재고 입출고 이력 조회
	 */
	@GetMapping("/v2/inventory/movements")
	public ResponseEntity<?> movements(@RequestParam Map<String, Object> p) {
		return execute(() -> inventoryService.movements(p));
	}

	/*
	 * part : 재고관리 method : move comment : 재고 수량 증감(입출고) 처리 및 이력 기록. 결과가 음수 재고가 되면 거부
	 */
	@PostMapping("/v2/inventory/movements")
	public ResponseEntity<?> move(@RequestBody Map<String, Object> b) {
		return execute(() -> inventoryService.move(b));
	}

	/*
	 * part : 재고관리 method : opening comment : 기초재고 등록. 도입 전 보유 재고를 수량과 기준단위당 단가로 등록(평균단가·재고금액 설정, OPENING 이력). 입고·사용 이력이 있으면 불가
	 */
	@PostMapping("/v2/inventory/opening")
	public ResponseEntity<?> opening(@RequestBody Map<String, Object> b) {
		return execute(() -> inventoryService.opening(b));
	}

	/*
	 * part : 본사 식자재 method : ingredients comment : 본사 식자재 마스터 조회. 본사 식자재 마스터는 거래처 재고와 수명주기가 달라 별도 API로 관리한다.
	 */
	@GetMapping("/v2/ingredients")
	public ResponseEntity<?> ingredients(@RequestParam Map<String, Object> p) {
		return execute(() -> inventoryService.ingredientMasters(p));
	}

	/*
	 * part : 본사 식자재 method : createIngredient comment : 본사 식자재 마스터 등록
	 */
	@PostMapping("/v2/ingredients")
	public ResponseEntity<?> createIngredient(@RequestBody Map<String, Object> b) {
		return execute(() -> inventoryService.createIngredientMaster(b));
	}

	/*
	 * part : 본사 식자재 method : updateIngredient comment : 본사 식자재 마스터 수정
	 */
	@PatchMapping("/v2/ingredients")
	public ResponseEntity<?> updateIngredient(@RequestBody Map<String, Object> b) {
		return execute(() -> inventoryService.updateIngredientMaster(b));
	}

	private ResponseEntity<?> execute(java.util.function.Supplier<?> a) {
		try {
			return ResponseEntity.ok(a.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
