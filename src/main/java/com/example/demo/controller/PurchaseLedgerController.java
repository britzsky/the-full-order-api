package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.PurchaseLedgerService;

/** 본사 전용(WorkspaceAccess): 매입 관리(ASP, 2026-11-23) — 매입 목록·합계, 공급사 청구 대조, 단가 부가세 기준 확인 */
@RestController
@RequestMapping("/v2/purchase-ledger")
public class PurchaseLedgerController {
	private final PurchaseLedgerService service;

	public PurchaseLedgerController(PurchaseLedgerService service) {
		this.service = service;
	}

	/*
	 * part : 매입 관리 method : summary comment : 기간 매입 합계(공급가·부가세·합계, 과세/면세/미확인, 발주·직접·이력) +
	 * group_by(supplier|account|month|day)별. 조건: from_date, to_date(필수), account_id, supplier_id, source, tax_type, check_status, keyword
	 */
	@GetMapping("/summary")
	public ResponseEntity<?> summary(@RequestParam Map<String, Object> params) {
		return execute(() -> service.summary(params));
	}

	/*
	 * part : 매입 관리 method : lines comment : 매입 줄 목록(최근 순, page·size 최대 500). 조건은 summary와 같음
	 */
	@GetMapping("/lines")
	public ResponseEntity<?> lines(@RequestParam Map<String, Object> params) {
		return execute(() -> service.lines(params));
	}

	/*
	 * part : 매입 관리 method : priceCheck comment : 공급사 발주 단가가 부가세 포함인지 청구 내역으로 확인 + 못 맞춘 청구 줄·청구됐는데 입고 없는 품목
	 */
	@GetMapping("/price-check")
	public ResponseEntity<?> priceCheck(@RequestParam Map<String, Object> params) {
		return execute(() -> service.priceCheck(params));
	}

	/*
	 * part : 매입 관리 method : recheck comment : 기간 안 청구 줄이 있는 발주 입고를 다시 대조(공급사 API는 부르지 않음)
	 */
	@PostMapping("/recheck")
	public ResponseEntity<?> recheck(@RequestBody Map<String, Object> body) {
		return execute(() -> service.recheck(body));
	}

	private ResponseEntity<?> execute(Supplier<?> action) {
		try {
			return ResponseEntity.ok(action.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
