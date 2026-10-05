package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.SupplierCatalogService;

@RestController
@RequestMapping("/v2/catalog")
public class SupplierCatalogController {
	private final SupplierCatalogService service;

	public SupplierCatalogController(SupplierCatalogService service) {
		this.service = service;
	}

	/*
	 * part : 공급사 카탈로그 method : suppliers comment : 공급사 목록 조회
	 */
	@GetMapping("/suppliers")
	public ResponseEntity<?> suppliers(@RequestParam Map<String, Object> p) {
		return ok(() -> service.suppliers(p));
	}

	/*
	 * part : 공급사 카탈로그 method : createSupplier comment : 공급사 등록
	 */
	@PostMapping("/suppliers")
	public ResponseEntity<?> createSupplier(@RequestBody Map<String, Object> b) {
		return ok(() -> service.createSupplier(b));
	}

	/*
	 * part : 공급사 카탈로그 method : updateSupplier comment : 공급사 수정
	 */
	@PatchMapping("/suppliers")
	public ResponseEntity<?> updateSupplier(@RequestBody Map<String, Object> b) {
		return ok(() -> service.updateSupplier(b));
	}

	/*
	 * part : 공급사 카탈로그 method : deleteSupplier comment : 공급사 삭제
	 */
	@DeleteMapping("/suppliers")
	public ResponseEntity<?> deleteSupplier(@RequestBody Map<String, Object> b) {
		return ok(() -> service.deleteSupplier(b));
	}

	/*
	 * part : 공급사 카탈로그 method : products comment : 공급상품 목록 조회
	 */
	@GetMapping("/products")
	public ResponseEntity<?> products(@RequestParam Map<String, Object> p) {
		return ok(() -> service.products(p));
	}

	/*
	 * part : 공급사 카탈로그 method : createProduct comment : 공급상품 등록
	 */
	@PostMapping("/products")
	public ResponseEntity<?> createProduct(@RequestBody Map<String, Object> b) {
		return ok(() -> service.createProduct(b));
	}

	/*
	 * part : 공급사 카탈로그 method : createProductWithPrice comment : 공급상품과 최초 가격 함께 등록. 식자재 관리 화면에서 공급상품과 최초 가격을 원자적으로 함께 등록한다.
	 */
	@PostMapping("/products-with-price")
	public ResponseEntity<?> createProductWithPrice(@RequestBody Map<String, Object> b) {
		return ok(() -> service.createProductWithPrice(b));
	}

	/*
	 * part : 공급사 카탈로그 method : updateProduct comment : 공급상품 수정
	 */
	@PatchMapping("/products")
	public ResponseEntity<?> updateProduct(@RequestBody Map<String, Object> b) {
		return ok(() -> service.updateProduct(b));
	}

	/*
	 * part : 공급사 카탈로그 method : updateProductWithPrice comment : 공급상품과 가격 함께 수정
	 */
	@PatchMapping("/products-with-price")
	public ResponseEntity<?> updateProductWithPrice(@RequestBody Map<String, Object> b) {
		return ok(() -> service.updateProductWithPrice(b));
	}

	/*
	 * part : 공급사 카탈로그 method : deleteProduct comment : 공급상품 삭제
	 */
	@DeleteMapping("/products")
	public ResponseEntity<?> deleteProduct(@RequestBody Map<String, Object> b) {
		return ok(() -> service.deleteProduct(b));
	}

	/*
	 * part : 공급사 카탈로그 method : prices comment : 공급상품 가격 이력 조회
	 */
	@GetMapping("/prices")
	public ResponseEntity<?> prices(@RequestParam Map<String, Object> p) {
		return ok(() -> service.prices(p));
	}

	/*
	 * part : 공급사 카탈로그 method : createPrice comment : 공급상품 가격 등록
	 */
	@PostMapping("/prices")
	public ResponseEntity<?> createPrice(@RequestBody Map<String, Object> b) {
		return ok(() -> service.createPrice(b));
	}

	/*
	 * part : 공급사 카탈로그 method : updatePrice comment : 공급상품 가격 수정
	 */
	@PatchMapping("/prices")
	public ResponseEntity<?> updatePrice(@RequestBody Map<String, Object> b) {
		return ok(() -> service.updatePrice(b));
	}

	/*
	 * part : 공급사 카탈로그 method : deletePrice comment : 공급상품 가격 삭제
	 */
	@DeleteMapping("/prices")
	public ResponseEntity<?> deletePrice(@RequestBody Map<String, Object> b) {
		return ok(() -> service.deletePrice(b));
	}

	/*
	 * part : 공급사 카탈로그 method : accountProducts comment : 거래처별 사용 공급상품 조회
	 */
	@GetMapping("/account-products")
	public ResponseEntity<?> accountProducts(@RequestParam Map<String, Object> p) {
		return ok(() -> service.accountProducts(p));
	}

	/*
	 * part : 공급사 카탈로그 method : createAccountProduct comment : 거래처 사용 공급상품 등록
	 */
	@PostMapping("/account-products")
	public ResponseEntity<?> createAccountProduct(@RequestBody Map<String, Object> b) {
		return ok(() -> service.createAccountProduct(b));
	}

	/*
	 * part : 공급사 카탈로그 method : updateAccountProduct comment : 거래처 사용 공급상품 수정
	 */
	@PatchMapping("/account-products")
	public ResponseEntity<?> updateAccountProduct(@RequestBody Map<String, Object> b) {
		return ok(() -> service.updateAccountProduct(b));
	}

	/*
	 * part : 공급사 카탈로그 method : deleteAccountProduct comment : 거래처 사용 공급상품 삭제
	 */
	@DeleteMapping("/account-products")
	public ResponseEntity<?> deleteAccountProduct(@RequestBody Map<String, Object> b) {
		return ok(() -> service.deleteAccountProduct(b));
	}

	private ResponseEntity<?> ok(Supplier<?> action) {
		try {
			return ResponseEntity.ok(action.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
