package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.MealDocumentService;

/**
 * 저장된 식단표로 만드는 부속 서류 내려받기. 거래처 범위는 WorkspaceSecurityConfig(account_id·table_id 주소 파라미터)가 확인.
 * 기준: docs/MEAL_DOCUMENTS_RULES.md
 */
@RestController
@RequestMapping("/v2/meal-plans/documents")
public class MealDocumentController {
	private final MealDocumentService service;

	public MealDocumentController(MealDocumentService service) {
		this.service = service;
	}

	/*
	 * part : 식단관리 method : document comment : 부속 서류 파일 — type: cooking-plan(조리계획표 엑셀)·operation-log(급식운영일지 엑셀)·retention-label(보존식 라벨 PDF)
	 */
	@GetMapping("/{type}")
	public ResponseEntity<?> document(@PathVariable("type") String type,
			@RequestParam(name = "account_id", defaultValue = "") String accountId,
			@RequestParam(name = "table_id", defaultValue = "") String tableId) {
		try {
			MealDocumentService.Document doc = service.build(type, accountId, tableId);
			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION,
							ContentDisposition.attachment().filename(doc.fileName(), StandardCharsets.UTF_8).build().toString())
					.header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
					.contentType(MediaType.parseMediaType(doc.contentType()))
					.body(doc.bytes());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
		}
	}
}
