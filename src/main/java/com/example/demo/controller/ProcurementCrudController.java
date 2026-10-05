package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.ProcurementCrudService;

@RestController
@RequestMapping("/v2/procurement")
public class ProcurementCrudController {
	private final ProcurementCrudService s;

	public ProcurementCrudController(ProcurementCrudService s) {
		this.s = s;
	}

	/*
	 * part : 발주관리 method : analysis comment : 식단 기준 식자재 소요 분석
	 */
	@GetMapping("/analysis")
	public ResponseEntity<?> analysis(@RequestParam Map<String, Object> p) {
		return run(() -> s.analysis(p));
	}

	/*
	 * part : 발주관리 method : shortages comment : 식단 기준 부족 식자재 조회
	 */
	@GetMapping("/shortages")
	public ResponseEntity<?> shortages(@RequestParam Map<String, Object> p) {
		return run(() -> s.shortages(p));
	}

	/*
	 * part : 발주관리 method : carts comment : 발주 장바구니 목록 조회
	 */
	@GetMapping("/carts")
	public ResponseEntity<?> carts(@RequestParam Map<String, Object> p) {
		return run(() -> s.carts(p));
	}

	/*
	 * part : 발주관리 method : createCart comment : 발주 장바구니 생성
	 */
	@PostMapping("/carts")
	public ResponseEntity<?> createCart(@RequestBody Map<String, Object> b) {
		return run(() -> s.createCart(b));
	}

	/*
	 * part : 발주관리 method : updateCart comment : 발주 장바구니 수정
	 */
	@PatchMapping("/carts")
	public ResponseEntity<?> updateCart(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateCart(b));
	}

	/*
	 * part : 발주관리 method : deleteCart comment : 발주 장바구니 삭제
	 */
	@DeleteMapping("/carts")
	public ResponseEntity<?> deleteCart(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteCart(b));
	}

	/*
	 * part : 발주관리 method : fromPlan comment : 식단의 부족 식자재로 발주 장바구니 자동 생성
	 */
	@PostMapping("/carts/from-meal-plan")
	public ResponseEntity<?> fromPlan(@RequestBody Map<String, Object> b) {
		return run(() -> s.createFromMealPlan(b));
	}

	/*
	 * part : 발주관리 method : cartItems comment : 장바구니 품목 조회
	 */
	@GetMapping("/cart-items")
	public ResponseEntity<?> cartItems(@RequestParam Map<String, Object> p) {
		return run(() -> s.cartItems(p));
	}

	/*
	 * part : 발주관리 method : createCartItem comment : 장바구니 품목 추가
	 */
	@PostMapping("/cart-items")
	public ResponseEntity<?> createCartItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.createCartItem(b));
	}

	/*
	 * part : 발주관리 method : updateCartItem comment : 장바구니 품목 수정
	 */
	@PatchMapping("/cart-items")
	public ResponseEntity<?> updateCartItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateCartItem(b));
	}

	/*
	 * part : 발주관리 method : deleteCartItem comment : 장바구니 품목 삭제
	 */
	@DeleteMapping("/cart-items")
	public ResponseEntity<?> deleteCartItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteCartItem(b));
	}

	/*
	 * part : 발주관리 method : orders comment : 발주서 목록 조회
	 */
	@GetMapping("/orders")
	public ResponseEntity<?> orders(@RequestParam Map<String, Object> p) {
		return run(() -> s.orders(p));
	}

	/*
	 * part : 발주관리 method : createOrder comment : 발주서 생성
	 */
	@PostMapping("/orders")
	public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> b) {
		return run(() -> s.createOrder(b));
	}

	/*
	 * part : 발주관리 method : updateOrder comment : 발주서 수정
	 */
	@PatchMapping("/orders")
	public ResponseEntity<?> updateOrder(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateOrder(b));
	}

	/*
	 * part : 발주관리 method : deleteOrder comment : 발주서 삭제
	 */
	@DeleteMapping("/orders")
	public ResponseEntity<?> deleteOrder(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteOrder(b));
	}

	/*
	 * part : 발주관리 method : orderItems comment : 발주서 품목 조회
	 */
	@GetMapping("/order-items")
	public ResponseEntity<?> orderItems(@RequestParam Map<String, Object> p) {
		return run(() -> s.orderItems(p));
	}

	/*
	 * part : 발주관리 method : createOrderItem comment : 발주서 품목 추가
	 */
	@PostMapping("/order-items")
	public ResponseEntity<?> createOrderItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.createOrderItem(b));
	}

	/*
	 * part : 발주관리 method : updateOrderItem comment : 발주서 품목 수정
	 */
	@PatchMapping("/order-items")
	public ResponseEntity<?> updateOrderItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateOrderItem(b));
	}

	/*
	 * part : 발주관리 method : deleteOrderItem comment : 발주서 품목 삭제
	 */
	@DeleteMapping("/order-items")
	public ResponseEntity<?> deleteOrderItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteOrderItem(b));
	}

	private ResponseEntity<?> run(Supplier<?> a) {
		try {
			return ResponseEntity.ok(a.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
