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

	@GetMapping("/analysis")
	public ResponseEntity<?> analysis(@RequestParam Map<String, Object> p) {
		return run(() -> s.analysis(p));
	}

	@GetMapping("/shortages")
	public ResponseEntity<?> shortages(@RequestParam Map<String, Object> p) {
		return run(() -> s.shortages(p));
	}

	@GetMapping("/carts")
	public ResponseEntity<?> carts(@RequestParam Map<String, Object> p) {
		return run(() -> s.carts(p));
	}

	@PostMapping("/carts")
	public ResponseEntity<?> createCart(@RequestBody Map<String, Object> b) {
		return run(() -> s.createCart(b));
	}

	@PatchMapping("/carts")
	public ResponseEntity<?> updateCart(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateCart(b));
	}

	@DeleteMapping("/carts")
	public ResponseEntity<?> deleteCart(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteCart(b));
	}

	@PostMapping("/carts/from-meal-plan")
	public ResponseEntity<?> fromPlan(@RequestBody Map<String, Object> b) {
		return run(() -> s.createFromMealPlan(b));
	}

	@GetMapping("/cart-items")
	public ResponseEntity<?> cartItems(@RequestParam Map<String, Object> p) {
		return run(() -> s.cartItems(p));
	}

	@PostMapping("/cart-items")
	public ResponseEntity<?> createCartItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.createCartItem(b));
	}

	@PatchMapping("/cart-items")
	public ResponseEntity<?> updateCartItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateCartItem(b));
	}

	@DeleteMapping("/cart-items")
	public ResponseEntity<?> deleteCartItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteCartItem(b));
	}

	@GetMapping("/orders")
	public ResponseEntity<?> orders(@RequestParam Map<String, Object> p) {
		return run(() -> s.orders(p));
	}

	@PostMapping("/orders")
	public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> b) {
		return run(() -> s.createOrder(b));
	}

	@PatchMapping("/orders")
	public ResponseEntity<?> updateOrder(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateOrder(b));
	}

	@DeleteMapping("/orders")
	public ResponseEntity<?> deleteOrder(@RequestBody Map<String, Object> b) {
		return run(() -> s.deleteOrder(b));
	}

	@GetMapping("/order-items")
	public ResponseEntity<?> orderItems(@RequestParam Map<String, Object> p) {
		return run(() -> s.orderItems(p));
	}

	@PostMapping("/order-items")
	public ResponseEntity<?> createOrderItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.createOrderItem(b));
	}

	@PatchMapping("/order-items")
	public ResponseEntity<?> updateOrderItem(@RequestBody Map<String, Object> b) {
		return run(() -> s.updateOrderItem(b));
	}

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
