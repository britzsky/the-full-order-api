package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.DistOrderAdminService;

/**
 * 더채움 유통 — 본사 주문 처리·거래처 결제 설정. 본사 전용(WorkspaceAccess '/v2/dist/admin'). JSON 본문 user_id는 WorkspaceBodyAdvice가 채운다.
 */
@RestController
@RequestMapping("/v2/dist/admin")
public class DistOrderAdminController {
	private final DistOrderAdminService service;

	public DistOrderAdminController(DistOrderAdminService service) {
		this.service = service;
	}

	/*
	 * part : 협력사 관리 method : orders comment : 전체 주문 + 일감 수. status, buyer_type, partner_id, from, to, keyword, work(need_deposit/need_po/need_invoice/cancel_requested)
	 */
	@GetMapping("/orders")
	public ResponseEntity<?> orders(@RequestParam Map<String, String> q) { return run(() -> service.orders(q)); }

	/*
	 * part : 협력사 관리 method : order comment : 주문 상세(주문자·배송지 복호화, 결제·발주서·송장·클레임·이력, 지금 할 수 있는 일)
	 */
	@GetMapping("/orders/{orderId}")
	public ResponseEntity<?> order(@PathVariable("orderId") String orderId) { return run(() -> service.order(orderId)); }

	/*
	 * part : 협력사 관리 method : saveNote comment : 본사 메모. body: admin_note
	 */
	@PostMapping("/orders/{orderId}/note")
	public ResponseEntity<?> saveNote(@PathVariable("orderId") String orderId, @RequestBody Map<String, Object> b) { return run(() -> service.saveNote(orderId, b)); }

	/*
	 * part : 협력사 관리 method : confirmDeposit comment : 무통장 입금 확인. body: amount, depositor_name, paid_at, note
	 */
	@PostMapping("/orders/{orderId}/deposit")
	public ResponseEntity<?> confirmDeposit(@PathVariable("orderId") String orderId, @RequestBody Map<String, Object> b) { return run(() -> service.confirmDeposit(orderId, b)); }

	/*
	 * part : 협력사 관리 method : createPurchaseOrder comment : 협력사 직송 발주서 만들기. body: partner_id, expected_date, note
	 */
	@PostMapping("/orders/{orderId}/purchase-orders")
	public ResponseEntity<?> createPurchaseOrder(@PathVariable("orderId") String orderId, @RequestBody Map<String, Object> b) { return run(() -> service.createPurchaseOrder(orderId, b)); }

	/*
	 * part : 협력사 관리 method : confirmPurchaseOrder comment : 협력사 확인(본사 대리 입력). body: expected_date, note
	 */
	@PostMapping("/purchase-orders/{purchaseOrderId}/confirm")
	public ResponseEntity<?> confirmPurchaseOrder(@PathVariable("purchaseOrderId") String purchaseOrderId, @RequestBody Map<String, Object> b) { return run(() -> service.confirmPurchaseOrder(purchaseOrderId, b)); }

	/*
	 * part : 협력사 관리 method : prepareOwnStock comment : 자사재고 상품 출고 준비 표시
	 */
	@PostMapping("/orders/{orderId}/prepare")
	public ResponseEntity<?> prepareOwnStock(@PathVariable("orderId") String orderId, @RequestBody Map<String, Object> b) { return run(() -> service.prepareOwnStock(orderId, b)); }

	/*
	 * part : 협력사 관리 method : addShipment comment : 송장 등록. body: carrier_code, tracking_no, items[{order_item_id, qty}]
	 */
	@PostMapping("/orders/{orderId}/shipments")
	public ResponseEntity<?> addShipment(@PathVariable("orderId") String orderId, @RequestBody Map<String, Object> b) { return run(() -> service.addShipment(orderId, b)); }

	/*
	 * part : 협력사 관리 method : markDelivered comment : 배송 완료 표시
	 */
	@PostMapping("/shipments/{shipmentId}/delivered")
	public ResponseEntity<?> markDelivered(@PathVariable("shipmentId") String shipmentId, @RequestBody Map<String, Object> b) { return run(() -> service.markDelivered(shipmentId, b)); }

	/*
	 * part : 협력사 관리 method : cancelByHq comment : 본사 취소(출고 전). body: reason_code, reason, refund{bank_name, account_no, holder}(입금된 주문)
	 */
	@PostMapping("/orders/{orderId}/cancel")
	public ResponseEntity<?> cancelByHq(@PathVariable("orderId") String orderId, @RequestBody Map<String, Object> b) { return run(() -> service.cancelByHq(orderId, b)); }

	/*
	 * part : 협력사 관리 method : approveClaim comment : 고객 취소 요청 승인(환불 기록 후 취소). body: refund{...}
	 */
	@PostMapping("/claims/{claimId}/approve")
	public ResponseEntity<?> approveClaim(@PathVariable("claimId") String claimId, @RequestBody Map<String, Object> b) { return run(() -> service.approveClaim(claimId, b)); }

	/*
	 * part : 협력사 관리 method : rejectClaim comment : 고객 취소 요청 거절. body: reason
	 */
	@PostMapping("/claims/{claimId}/reject")
	public ResponseEntity<?> rejectClaim(@PathVariable("claimId") String claimId, @RequestBody Map<String, Object> b) { return run(() -> service.rejectClaim(claimId, b)); }

	/*
	 * part : 협력사 관리 method : accountProfiles comment : 거래처 결제 설정 목록. keyword, only_set(설정·주문 있는 거래처만)
	 */
	@GetMapping("/account-profiles")
	public ResponseEntity<?> accountProfiles(@RequestParam(name = "keyword", defaultValue = "") String keyword,
			@RequestParam(name = "only_set", defaultValue = "true") boolean onlySet) {
		return run(() -> service.accountProfiles(keyword, onlySet));
	}

	/*
	 * part : 협력사 관리 method : accountProfile comment : 거래처 결제 설정 하나
	 */
	@GetMapping("/account-profiles/{accountId}")
	public ResponseEntity<?> accountProfile(@PathVariable("accountId") String accountId) { return run(() -> service.accountProfile(accountId)); }

	/*
	 * part : 협력사 관리 method : saveAccountProfile comment : 거래처 결제 설정 저장(후불 허용·한도·정지·주문 허용·기본 배송지)
	 */
	@PostMapping("/account-profiles")
	public ResponseEntity<?> saveAccountProfile(@RequestBody Map<String, Object> b) { return run(() -> service.saveAccountProfile(b)); }

	private ResponseEntity<?> run(Supplier<?> action) {
		try {
			return ResponseEntity.ok(action.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", 503, "message", e.getMessage()));
		}
	}
}
