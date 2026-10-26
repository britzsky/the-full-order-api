package com.example.demo.controller;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.example.demo.security.WorkspaceAccess;
import com.example.demo.security.WorkspaceUser;
import com.example.demo.service.HygieneService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 위생관리(기존 ERP 위생관리 데이터를 그대로 씀). 조회·기록 저장은 본사·현장 모두, 카테고리 저장은 본사(WorkspaceAccess), 기록 삭제는 본사(HygieneService).
 * 거래처 범위: 주소 파라미터는 WorkspaceSecurityConfig, JSON 본문은 WorkspaceBodyAdvice가 확인(현장은 자기 거래처로 고정).
 */
@RestController
@RequestMapping("/v2/hygiene")
public class HygieneController {
	private final HygieneService service;

	public HygieneController(HygieneService service) {
		this.service = service;
	}

	/*
	 * part : 위생관리 method : categories comment : 거래처 위생 점검 카테고리(ERP HygieneCategoryList)
	 */
	@GetMapping("/categories")
	public ResponseEntity<?> categories(@RequestParam(name = "account_id", defaultValue = "") String accountId) {
		return run(() -> service.categories(accountId));
	}

	/*
	 * part : 위생관리 method : saveCategories comment : 거래처 카테고리 목록 전체 저장(ERP HygieneCategorySave, 본사 전용)
	 */
	@PostMapping("/categories")
	public ResponseEntity<?> saveCategories(@RequestBody Map<String, Object> body) {
		return run(() -> service.saveCategories(Objects.toString(body.get("account_id"), ""), body.get("categories")));
	}

	/*
	 * part : 위생관리 method : records comment : 점검 기록 — year·month가 있으면 그 달(ERP HygieneList), 없으면 누적(ERP HygieneCheckList)
	 */
	@GetMapping("/records")
	public ResponseEntity<?> records(@RequestParam(name = "account_id", defaultValue = "") String accountId,
			@RequestParam(name = "year", defaultValue = "") String year, @RequestParam(name = "month", defaultValue = "") String month) {
		return run(() -> service.records(accountId, year, month));
	}

	/*
	 * part : 위생관리 method : saveRecords comment : 점검 기록 저장·수정·삭제(ERP HygieneSave) — { account_id, records: [...] }
	 */
	@PostMapping("/records")
	public ResponseEntity<?> saveRecords(@RequestBody Map<String, Object> body, HttpServletRequest request) {
		WorkspaceUser user = WorkspaceAccess.user(request);
		return run(() -> service.saveRecords(Objects.toString(body.get("account_id"), ""), body.get("records"), user.userId(), user.headquarters()));
	}

	/*
	 * part : 위생관리 method : uploadPhoto comment : 점검 사진 올리기(ERP OperateImgUpload, hygiene/날짜/거래처)
	 */
	@PostMapping("/photos")
	public ResponseEntity<?> uploadPhoto(@RequestParam(name = "account_id", defaultValue = "") String accountId,
			@RequestParam("file") MultipartFile file) {
		return run(() -> service.uploadPhoto(accountId, file));
	}

	private ResponseEntity<?> run(Supplier<?> action) {
		try {
			return ResponseEntity.ok(action.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("code", 502, "message", e.getMessage()));
		}
	}
}
