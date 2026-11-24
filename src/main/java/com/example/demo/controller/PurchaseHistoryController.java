package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.PurchaseHistoryImportService;
import com.example.demo.service.PurchaseHistoryService;

/** 본사 전용: 고객사 초기 세팅용 공급사 구매 이력 가져오기·식재료 연결·단가 이력(2026-11-19) */
@RestController
@RequestMapping("/v2/purchase-history")
public class PurchaseHistoryController {
	private final PurchaseHistoryService service;
	private final PurchaseHistoryImportService importer;

	public PurchaseHistoryController(PurchaseHistoryService service, PurchaseHistoryImportService importer) {
		this.service = service;
		this.importer = importer;
	}

	/*
	 * part : 구매 이력 method : settings comment : 거래처 재고 시작일 조회
	 */
	@GetMapping("/settings")
	public ResponseEntity<?> settings(@RequestParam(name = "account_id") String accountId) {
		return execute(() -> service.settings(accountId));
	}

	/*
	 * part : 구매 이력 method : saveSettings comment : 재고 시작일 저장(비우면 미정). 이미 가져온 이력도 HISTORY/UNMATCHED로 다시 나눈다
	 */
	@PostMapping("/settings")
	public ResponseEntity<?> saveSettings(@RequestBody Map<String, Object> body) {
		return execute(() -> service.saveSettings(body));
	}

	/*
	 * part : 구매 이력 method : startImport comment : 공급사 구매 이력 가져오기 시작(account_id, from_date, to_date, supplier_code 선택). 바로 돌려주고 뒤에서 진행
	 */
	@PostMapping("/imports")
	public ResponseEntity<?> startImport(@RequestBody Map<String, Object> body) {
		return execute(() -> importer.start(body));
	}

	/*
	 * part : 구매 이력 method : imports comment : 최근 가져오기 작업 목록(서버 메모리, 재시작하면 비워짐)
	 */
	@GetMapping("/imports")
	public ResponseEntity<?> imports(@RequestParam(name = "account_id", required = false) String accountId) {
		return execute(() -> Map.of("jobs", importer.jobs(accountId)));
	}

	/*
	 * part : 구매 이력 method : importJob comment : 가져오기 진행 상황
	 */
	@GetMapping("/imports/{jobId}")
	public ResponseEntity<?> importJob(@PathVariable("jobId") String jobId) {
		return execute(() -> importer.job(jobId));
	}

	/*
	 * part : 구매 이력 method : stopImport comment : 가져오기 멈춤(받은 줄은 남고, 다시 돌리면 이어서 덮어씀)
	 */
	@PostMapping("/imports/{jobId}/stop")
	public ResponseEntity<?> stopImport(@PathVariable("jobId") String jobId) {
		return execute(() -> importer.stop(jobId));
	}

	/*
	 * part : 구매 이력 method : products comment : 산 상품 목록(공급가액 큰 순, ABC 분류, 연결된 식재료). unlinked_only=Y면 연결 안 된 상품만
	 */
	@GetMapping("/products")
	public ResponseEntity<?> products(@RequestParam Map<String, Object> params) {
		return execute(() -> service.products(params));
	}

	/*
	 * part : 구매 이력 method : link comment : 산 상품을 거래처 식재료에 연결(ingredient_id 비우면 해제). 원가 계산의 대표 상품이 된다
	 */
	@PostMapping("/links")
	public ResponseEntity<?> link(@RequestBody Map<String, Object> body) {
		return execute(() -> service.link(body));
	}

	/*
	 * part : 구매 이력 method : ingredientPrices comment : 식재료별 기준단위당 단가(가중평균·최저·최고·차이 표시)
	 */
	@GetMapping("/ingredient-prices")
	public ResponseEntity<?> ingredientPrices(@RequestParam Map<String, Object> params) {
		return execute(() -> service.ingredientPrices(params));
	}

	/*
	 * part : 구매 이력 method : ingredientPriceLines comment : 식재료 한 개의 입고 줄별 단가와 평균 대비 차이
	 */
	@GetMapping("/ingredient-prices/lines")
	public ResponseEntity<?> ingredientPriceLines(@RequestParam Map<String, Object> params) {
		return execute(() -> service.ingredientPriceLines(params));
	}

	private ResponseEntity<?> execute(Supplier<?> action) {
		try {
			return ResponseEntity.ok(action.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
