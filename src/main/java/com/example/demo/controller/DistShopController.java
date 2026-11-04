package com.example.demo.controller;

import java.util.Map;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.security.WorkspaceAccess;
import com.example.demo.security.WorkspaceUser;
import com.example.demo.service.DistShopService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 더채움몰 — 본사·현장 누구나(로그인 필요). 사용자·거래처는 항상 로그인 세션에서 읽는다(본문·주소 값은 쓰지 않음).
 * 주문 조회·취소는 주문한 사람 본인만(DistShopService).
 */
@RestController
@RequestMapping("/v2/dist/shop")
public class DistShopController {
	private final DistShopService service;

	public DistShopController(DistShopService service) {
		this.service = service;
	}

	/*
	 * part : 더채움몰 method : context comment : 구매 유형·거래처·후불 가능 여부·입금 계좌·카테고리
	 */
	@GetMapping("/context")
	public ResponseEntity<?> context(HttpServletRequest request) { return run(request, service::context); }

	/*
	 * part : 더채움몰 method : products comment : 판매 중인 상품(구매 유형별 적용 가격). buyer_type, category_id, keyword
	 */
	@GetMapping("/products")
	public ResponseEntity<?> products(@RequestParam Map<String, String> q, HttpServletRequest request) { return run(request, user -> service.products(user, q)); }

	/*
	 * part : 더채움몰 method : product comment : 상품 상세(설명·이미지·남은 수량)
	 */
	@GetMapping("/products/{productId}")
	public ResponseEntity<?> product(@PathVariable("productId") String productId, @RequestParam(name = "buyer_type", defaultValue = "") String buyerType,
			HttpServletRequest request) {
		return run(request, user -> service.product(user, productId, buyerType));
	}

	/*
	 * part : 더채움몰 method : quote comment : 장바구니 금액(상품·배송비·세금·후불 가능) 계산, 저장 안 함
	 */
	@PostMapping("/quote")
	public ResponseEntity<?> quote(@RequestBody Map<String, Object> b, HttpServletRequest request) { return run(request, user -> service.quote(user, b)); }

	/*
	 * part : 더채움몰 method : placeOrder comment : 주문(같은 client_request_key 재요청은 기존 주문 반환)
	 */
	@PostMapping("/orders")
	public ResponseEntity<?> placeOrder(@RequestBody Map<String, Object> b, HttpServletRequest request) { return run(request, user -> service.placeOrder(user, b)); }

	/*
	 * part : 더채움몰 method : myOrders comment : 내가 주문한 목록. status=ACTIVE(진행 중)/DONE(끝난 주문)/빈값(전체)
	 */
	@GetMapping("/orders")
	public ResponseEntity<?> myOrders(@RequestParam(name = "status", defaultValue = "") String status, HttpServletRequest request) {
		return run(request, user -> service.myOrders(user, status));
	}

	/*
	 * part : 더채움몰 method : myOrder comment : 내 주문 상세(상품·입금 안내·배송조회·상태 이력·취소 가능 여부)
	 */
	@GetMapping("/orders/{orderId}")
	public ResponseEntity<?> myOrder(@PathVariable("orderId") String orderId, HttpServletRequest request) { return run(request, user -> service.myOrder(user, orderId)); }

	/*
	 * part : 더채움몰 method : cancel comment : 내 주문 취소(입금 전·후불은 바로, 입금 확인 후는 취소 요청). body: reason_code, reason
	 */
	@PostMapping("/orders/{orderId}/cancel")
	public ResponseEntity<?> cancel(@PathVariable("orderId") String orderId, @RequestBody Map<String, Object> b, HttpServletRequest request) {
		return run(request, user -> service.cancel(user, orderId, b));
	}

	private ResponseEntity<?> run(HttpServletRequest request, Function<WorkspaceUser, ?> action) {
		try {
			return ResponseEntity.ok(action.apply(WorkspaceAccess.user(request)));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", 503, "message", e.getMessage()));
		}
	}
}
