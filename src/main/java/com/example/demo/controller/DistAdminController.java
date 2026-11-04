package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.security.WorkspaceAccess;
import com.example.demo.service.DistAdminService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 더채움 유통 — 본사 '협력사 관리'(판매 주체·협력사·카테고리·상품·가격·판매 가능·이미지). 전부 본사 전용(WorkspaceAccess '/v2/dist/admin').
 * 주문하는 쪽(더채움몰)은 별도 경로로 둔다. JSON 본문의 user_id는 WorkspaceBodyAdvice가 로그인 사용자로 채운다.
 */
@RestController
@RequestMapping("/v2/dist/admin")
public class DistAdminController {
	private final DistAdminService service;

	public DistAdminController(DistAdminService service) {
		this.service = service;
	}

	/*
	 * part : 협력사 관리 method : seller comment : 판매 주체(더채움 사업자 정보) 조회
	 */
	@GetMapping("/seller")
	public ResponseEntity<?> seller() { return run(service::seller); }

	/*
	 * part : 협력사 관리 method : saveSeller comment : 판매 주체 저장(없으면 등록). body: seller_name, biz_no, ceo_name, ...
	 */
	@PutMapping("/seller")
	public ResponseEntity<?> saveSeller(@RequestBody Map<String, Object> b) { return run(() -> service.saveSeller(b)); }

	/*
	 * part : 협력사 관리 method : partners comment : 협력사 목록(상품 수·판매중 수) + 택배사 코드
	 */
	@GetMapping("/partners")
	public ResponseEntity<?> partners() { return run(service::partners); }

	/*
	 * part : 협력사 관리 method : savePartner comment : 협력사 등록(partner_id 없음)·수정
	 */
	@PostMapping("/partners")
	public ResponseEntity<?> savePartner(@RequestBody Map<String, Object> b) { return run(() -> service.savePartner(b)); }

	/*
	 * part : 협력사 관리 method : categories comment : 상품 카테고리 목록
	 */
	@GetMapping("/categories")
	public ResponseEntity<?> categories() { return run(service::categories); }

	/*
	 * part : 협력사 관리 method : saveCategory comment : 카테고리 등록(category_id 없음)·수정
	 */
	@PostMapping("/categories")
	public ResponseEntity<?> saveCategory(@RequestBody Map<String, Object> b) { return run(() -> service.saveCategory(b)); }

	/*
	 * part : 협력사 관리 method : products comment : 상품 목록. partner_id, category_id, sale_status, fulfillment_type, keyword
	 */
	@GetMapping("/products")
	public ResponseEntity<?> products(@RequestParam Map<String, String> q) { return run(() -> service.products(q)); }

	/*
	 * part : 협력사 관리 method : product comment : 상품 상세(기본·상세설명·이미지·판매가·매입가·판매 가능·변경 이력)
	 */
	@GetMapping("/products/{productId}")
	public ResponseEntity<?> product(@PathVariable("productId") String productId) { return run(() -> service.product(productId)); }

	/*
	 * part : 협력사 관리 method : saveProduct comment : 상품 등록(product_id 없음, 첫 가격 account_price·personal_price·unit_cost 함께 가능)·수정
	 */
	@PostMapping("/products")
	public ResponseEntity<?> saveProduct(@RequestBody Map<String, Object> b) { return run(() -> service.saveProduct(b)); }

	/*
	 * part : 협력사 관리 method : changeStatus comment : 판매 상태 변경. body: product_id, sale_status, reason
	 */
	@PostMapping("/products/status")
	public ResponseEntity<?> changeStatus(@RequestBody Map<String, Object> b) { return run(() -> service.changeStatus(b)); }

	/*
	 * part : 협력사 관리 method : addSalePrice comment : 판매가 추가(이전 가격은 시작 시각에 닫힘). body: product_id, price_type, account_id, unit_price, start_at, change_reason
	 */
	@PostMapping("/products/sale-prices")
	public ResponseEntity<?> addSalePrice(@RequestBody Map<String, Object> b) { return run(() -> service.addSalePrice(b, b.get("user_id"))); }

	/*
	 * part : 협력사 관리 method : endSalePrice comment : 판매가 종료(행사 끝 등). body: product_id, sale_price_id, end_at
	 */
	@PostMapping("/products/sale-prices/end")
	public ResponseEntity<?> endSalePrice(@RequestBody Map<String, Object> b) { return run(() -> service.endSalePrice(b)); }

	/*
	 * part : 협력사 관리 method : addCostPrice comment : 매입가 추가(이전 매입가는 전날로 닫힘). body: product_id, unit_cost, start_date, change_reason
	 */
	@PostMapping("/products/cost-prices")
	public ResponseEntity<?> addCostPrice(@RequestBody Map<String, Object> b) { return run(() -> service.addCostPrice(b, b.get("user_id"))); }

	/*
	 * part : 협력사 관리 method : saveAvailability comment : 기간별 판매 가능 수량 등록·수정. body: product_id, availability_id, available_from, available_to, available_qty, status, note
	 */
	@PostMapping("/products/availability")
	public ResponseEntity<?> saveAvailability(@RequestBody Map<String, Object> b) { return run(() -> service.saveAvailability(b)); }

	/*
	 * part : 협력사 관리 method : addImage comment : 상품 이미지 주소 추가. body: product_id, image_url, image_type
	 */
	@PostMapping("/products/images")
	public ResponseEntity<?> addImage(@RequestBody Map<String, Object> b) { return run(() -> service.addImage(b)); }

	/*
	 * part : 협력사 관리 method : deleteImage comment : 상품 이미지 빼기
	 */
	@DeleteMapping("/products/images")
	public ResponseEntity<?> deleteImage(@RequestParam("product_id") String productId, @RequestParam("product_image_id") String imageId,
			HttpServletRequest request) {
		return run(() -> service.deleteImage(productId, imageId, WorkspaceAccess.user(request).userId()));
	}

	private ResponseEntity<?> run(Supplier<?> action) {
		try {
			return ResponseEntity.ok(action.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", e.getMessage()));
		}
	}
}
