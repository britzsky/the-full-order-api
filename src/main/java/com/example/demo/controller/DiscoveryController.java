package com.example.demo.controller;

import java.time.LocalDate;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.mapper.DiscoveryMapper;

/** 검색 결과와 메뉴 추천을 페이지 크기로 제한하여 제공한다. */
@RestController
@RequestMapping("/v2/discovery")
public class DiscoveryController {
	private final DiscoveryMapper mapper;

	public DiscoveryController(DiscoveryMapper mapper) {
		this.mapper = mapper;
	}

	/*
	 * part : 통합검색 method : search comment : 거래처 기준 통합 검색 (검색어 최대 100자, 결과 최대 30건)
	 */
	@GetMapping("/search")
	public ResponseEntity<?> search(@RequestParam Map<String, Object> params) {
		if (text(params.get("account_id")).isBlank())
			return ResponseEntity.badRequest().body(Map.of("message", "거래처를 확인하세요."));
		String query = text(params.get("q")).trim();
		if (query.isBlank())
			return ResponseEntity.ok(List.of());
		params.put("query", query.substring(0, Math.min(query.length(), 100)));
		params.put("limit", 30);
		return ResponseEntity.ok(mapper.search(params));
	}

	/*
	 * part : 메뉴추천 method : menus comment : 식단일 기준 추천 메뉴 조회 (즐겨찾기·사용 이력·최근 7일 미사용 등 추천 사유 포함, 최대 30건)
	 */
	@GetMapping("/menus")
	public ResponseEntity<?> menus(@RequestParam Map<String, Object> params) {
		if (text(params.get("account_id")).isBlank())
			return ResponseEntity.badRequest().body(Map.of("message", "거래처를 확인하세요."));
		params.putIfAbsent("meal_date", LocalDate.now().toString());
		LocalDate.parse(text(params.get("meal_date")));
		params.put("limit", 30);
		List<Map<String, Object>> result = mapper.recommendedMenus(params);
		for (var row : result) {
			List<String> reasons = new ArrayList<>();
			if (Integer.parseInt(text(row.get("favorite"))) > 0)
				reasons.add("즐겨찾기 메뉴");
			if (Integer.parseInt(text(row.get("usage_count"))) > 0)
				reasons.add("거래처 식단 사용 이력");
			if (Integer.parseInt(text(row.get("recent_count"))) == 0)
				reasons.add("최근 7일 미사용");
			row.put("reasons", reasons);
		}
		return ResponseEntity.ok(result);
	}

	private String text(Object value) {
		return value == null ? "" : value.toString();
	}
}
