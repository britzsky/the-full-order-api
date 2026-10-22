package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.SupplierIntegrationService;
import com.example.demo.service.WelstoryCatalogSyncService;
import com.example.demo.service.WelstoryCycleSyncService;

@RestController
@RequestMapping("/v2/supplier-integration")
public class SupplierIntegrationController {
	private final SupplierIntegrationService service;
	private final WelstoryCatalogSyncService welstorySyncService;
	private final WelstoryCycleSyncService welstoryCycleService;

	public SupplierIntegrationController(SupplierIntegrationService service,
			WelstoryCatalogSyncService welstorySyncService, WelstoryCycleSyncService welstoryCycleService) {
		this.service = service;
		this.welstorySyncService = welstorySyncService;
		this.welstoryCycleService = welstoryCycleService;
	}

	/*
	 * part : 공급사 연동 method : sites comment : 거래처의 공급사 사업장 연동 정보 조회
	 */
	@GetMapping("/sites")
	public ResponseEntity<?> sites(@RequestParam Map<String, Object> params) {
		return execute(() -> service.sites(params));
	}

	/*
	 * part : 공급사 연동 method : saveSite comment : 공급사 사업장 연동 정보 저장
	 */
	@PostMapping("/sites")
	public ResponseEntity<?> saveSite(@RequestBody Map<String, Object> body) {
		return execute(() -> service.saveSite(body));
	}

	/*
	 * part : 공급사 연동 method : offers comment : 공급상품 판매조건(offer)·단가 조회
	 */
	@GetMapping("/offers")
	public ResponseEntity<?> offers(@RequestParam Map<String, Object> params, jakarta.servlet.http.HttpServletRequest request) {
		// 하트(나만의 식재료) 표시·맨 위 정렬은 로그인 주인(현장=거래처, 본사=사용자) 기준
		return execute(() -> service.offers(com.example.demo.security.FavoriteOwner.of(request, params).applyTo(params)));
	}

	/*
	 * part : 공급사 연동 method : favorite comment : 나만의 식재료 지정·해제(상품 하트). body: supplier_product_id, favorite(Y/N)
	 */
	@PostMapping("/favorites")
	public ResponseEntity<?> favorite(@RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest request) {
		return execute(() -> service.favorite(com.example.demo.security.FavoriteOwner.of(request, body).applyTo(body)));
	}

	/*
	 * part : 공급사 연동 method : ingestOffer comment : 공급사 판매조건·단가 수집 반영 (기존 단가는 종료하고 신규 단가 이력 추가)
	 */
	@PostMapping("/offers/ingest")
	public ResponseEntity<?> ingestOffer(@RequestBody Map<String, Object> body) {
		return execute(() -> service.ingestOffer(body));
	}

	/*
	 * part : 공급사 연동 method : syncStatus comment : 공급사 동기화 상태 조회
	 */
	@GetMapping("/sync-status")
	public ResponseEntity<?> syncStatus(@RequestParam Map<String, Object> params) {
		return execute(() -> service.syncStatus(params));
	}

	/*
	 * part : 공급사 연동 method : receipts comment : 공급사 입고 내역 조회
	 */
	@GetMapping("/receipts")
	public ResponseEntity<?> receipts(@RequestParam Map<String, Object> params) {
		return execute(() -> service.receipts(params));
	}

	/*
	 * part : 공급사 연동 method : incidents comment : 공급사 연동 오류/이슈 내역 조회
	 */
	@GetMapping("/incidents")
	public ResponseEntity<?> incidents(@RequestParam Map<String, Object> params) {
		return execute(() -> service.incidents(params));
	}

	/*
	 * part : 웰스토리 연동 method : syncWelstorySites comment : 웰스토리 사업장 목록 동기화
	 */
	@PostMapping("/sync/welstory/sites")
	public ResponseEntity<?> syncWelstorySites(@RequestBody Map<String, Object> body) {
		return execute(() -> welstorySyncService.syncSites(body));
	}

	/*
	 * part : 웰스토리 연동 method : syncWelstoryCatalog comment : 웰스토리 품목·단가 카탈로그 동기화
	 */
	@PostMapping("/sync/welstory/catalog")
	public ResponseEntity<?> syncWelstoryCatalog(@RequestBody Map<String, Object> body) {
		return execute(() -> welstorySyncService.syncCatalog(body));
	}

	/*
	 * part : 웰스토리 연동 method : syncWelstoryAuto comment : 순기 번호 입력 없이 오늘 순기·차순기 판가 동기화(안 받은 순기는 전체 판가, 받은 순기는 변경 판가)
	 */
	@PostMapping("/sync/welstory/auto")
	public ResponseEntity<?> syncWelstoryAuto(@RequestBody Map<String, Object> body) {
		return execute(() -> Map.of("code", 200, "sites", welstoryCycleService.syncAccount(
				body.get("account_id") == null ? null : body.get("account_id").toString(),
				body.get("user_id") == null ? null : body.get("user_id").toString())));
	}

	/*
	 * part : 웰스토리 연동 method : syncWelstoryReceipts comment : 웰스토리 입고 내역 동기화 및 입고 처리
	 */
	@PostMapping("/sync/welstory/receipts")
	public ResponseEntity<?> syncWelstoryReceipts(@RequestBody Map<String, Object> body) {
		return execute(() -> welstorySyncService.syncReceipts(body));
	}

	private ResponseEntity<?> execute(Supplier<?> action) {
		try {
			return ResponseEntity.ok(action.get());
		} catch (IllegalArgumentException exception) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", exception.getMessage()));
		}
	}
}
