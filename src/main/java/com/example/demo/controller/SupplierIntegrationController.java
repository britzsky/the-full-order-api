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

@RestController
@RequestMapping("/v2/supplier-integration")
public class SupplierIntegrationController {
	private final SupplierIntegrationService service;
	private final WelstoryCatalogSyncService welstorySyncService;

	public SupplierIntegrationController(SupplierIntegrationService service,
			WelstoryCatalogSyncService welstorySyncService) {
		this.service = service;
		this.welstorySyncService = welstorySyncService;
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
	public ResponseEntity<?> offers(@RequestParam Map<String, Object> params) {
		return execute(() -> service.offers(params));
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
