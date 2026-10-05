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

import com.example.demo.service.OurhomeCatalogSyncService;
import com.example.demo.service.SupplierSiteService;
import com.example.demo.service.WelstoryCatalogSyncService;

@RestController
@RequestMapping("/v2/supplier-integration/site-mapping")
public class SupplierSiteController {
	private final SupplierSiteService service;
	private final WelstoryCatalogSyncService welstory;
	private final OurhomeCatalogSyncService ourhome;

	public SupplierSiteController(SupplierSiteService service, WelstoryCatalogSyncService welstory,
			OurhomeCatalogSyncService ourhome) {
		this.service = service;
		this.welstory = welstory;
		this.ourhome = ourhome;
	}

	/* part : 공급사 사업장 매핑 method : sites comment : 공급사 사업장 마스터와 지정된 거래처 조회 */
	@GetMapping("/sites")
	public ResponseEntity<?> sites(@RequestParam(name = "supplier_code", defaultValue = "") String supplierCode) {
		return execute(() -> service.masterSites(supplierCode));
	}

	/* part : 공급사 사업장 매핑 method : syncSites comment : 공급사 API에서 전체 사업장 목록을 받아 마스터만 갱신 (거래처 연결 없음) */
	@PostMapping("/sites/sync")
	public ResponseEntity<?> syncSites(@RequestBody Map<String, Object> body) {
		String supplierCode = text(body.get("supplier_code"));
		return execute(() -> switch (supplierCode) {
		case "WELSTORY" -> welstory.syncSites(body);
		case "OURHOME" -> ourhome.syncSites(text(body.get("user_id")));
		default -> throw new IllegalArgumentException("supplier_code는 WELSTORY 또는 OURHOME이어야 합니다.");
		});
	}

	/* part : 공급사 사업장 매핑 method : siteUse comment : 사업장을 매핑 대상에서 제외/복원 (본사·마감용 등) */
	@PostMapping("/sites/use")
	public ResponseEntity<?> siteUse(@RequestBody Map<String, Object> body) {
		return execute(() -> service.setUse(body.get("supplier_site_id"), text(body.get("use_yn")), text(body.get("user_id"))));
	}

	/* part : 공급사 사업장 매핑 method : mappings comment : 전체 거래처의 사용 중 공급사 사업장 매핑 조회 */
	@GetMapping("/mappings")
	public ResponseEntity<?> mappings() {
		return execute(service::mappings);
	}

	/* part : 공급사 사업장 매핑 method : assign comment : 거래처의 공급사 사업장 지정/변경/해제 (external_site_code 빈 값이면 해제) */
	@PostMapping("/assign")
	public ResponseEntity<?> assign(@RequestBody Map<String, Object> body) {
		return execute(() -> service.assign(text(body.get("account_id")), text(body.get("supplier_code")),
				text(body.get("external_site_code")), "Y".equals(body.get("allow_shared")), text(body.get("user_id"))));
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	private ResponseEntity<?> execute(Supplier<?> action) {
		try {
			return ResponseEntity.ok().header("Cache-Control", "no-store").body(action.get());
		} catch (SupplierSiteService.SharedSiteException exception) {
			return ResponseEntity.status(409).body(Map.of("code", "SHARED_SITE", "message", exception.getMessage(),
					"account_ids", exception.accountIds()));
		} catch (IllegalArgumentException exception) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", exception.getMessage()));
		} catch (IllegalStateException exception) {
			return ResponseEntity.status(502).body(Map.of("code", 502, "message", exception.getMessage()));
		}
	}
}
