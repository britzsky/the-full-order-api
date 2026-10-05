package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.AnalyticsService;

@RestController
@RequestMapping("/Analytics")
public class AnalyticsController {
	private final AnalyticsService analyticsService;

	public AnalyticsController(AnalyticsService analyticsService) {
		this.analyticsService = analyticsService;
	}

	/*
	 * part : 검색분석 method : saveSearch comment : 검색어 로그 저장 (검색 세션 ID 반환)
	 */
	@PostMapping("/Search")
	public ResponseEntity<Map<String, Object>> saveSearch(@RequestBody Map<String, Object> payload) {
		return ResponseEntity.ok(analyticsService.saveSearch(payload));
	}

	/*
	 * part : 검색분석 method : saveAction comment : 검색 결과 클릭 등 사용자 행동 로그 저장
	 */
	@PostMapping("/Action")
	public ResponseEntity<Map<String, Object>> saveAction(@RequestBody Map<String, Object> payload) {
		analyticsService.saveAction(payload);
		return ResponseEntity.accepted().body(Map.of("saved", true));
	}

	/*
	 * part : 검색분석 method : popularSearches comment : 거래처별 인기 검색어 조회 (기간 1~365일, 최대 50건)
	 */
	@GetMapping("/PopularSearches")
	public List<Map<String, Object>> popularSearches(
			@RequestParam(name = "account_id", defaultValue = "") String account_id,
			@RequestParam(name = "days", defaultValue = "30") int days,
			@RequestParam(name = "limit", defaultValue = "10") int limit) {
		return analyticsService.popularSearches(account_id, days, limit);
	}

	/*
	 * part : 검색분석 method : searchDashboard comment : 검색 분석 대시보드 조회 (인기 검색어·검색 의도 요약 등)
	 */
	@GetMapping("/Dashboard")
	public Map<String, Object> searchDashboard(@RequestParam(name = "account_id", defaultValue = "") String account_id,
			@RequestParam(name = "days", defaultValue = "30") int days,
			@RequestParam(name = "limit", defaultValue = "10") int limit) {
		return analyticsService.searchDashboard(account_id, days, limit);
	}

	/*
	 * part : 메뉴추천 method : saveRecommendation comment : 추천 결과 이력 저장 (추천 ID 반환)
	 */
	@PostMapping("/Recommendation")
	public ResponseEntity<Map<String, Object>> saveRecommendation(@RequestBody Map<String, Object> payload) {
		return ResponseEntity.ok(Map.of("recommendation_id", analyticsService.saveRecommendation(payload)));
	}

	/*
	 * part : 메뉴추천 method : saveRecommendationFeedback comment : 추천 결과 채택 여부(accepted_yn) 피드백 저장
	 */
	@PostMapping("/Recommendation/Feedback")
	public ResponseEntity<Map<String, Object>> saveRecommendationFeedback(@RequestBody Map<String, Object> payload) {
		return ResponseEntity.ok(Map.of("updated", analyticsService.saveRecommendationFeedback(payload)));
	}

	/*
	 * part : 위생관리 method : hygieneGuides comment : 상황 코드별 위생 대응 가이드 조회
	 */
	@GetMapping("/Hygiene/Guides")
	public List<Map<String, Object>> hygieneGuides(@RequestParam Map<String, Object> params) {
		return analyticsService.hygieneGuides(params);
	}

	/*
	 * part : 위생관리 method : saveHygieneIncident comment : 위생 사고(이슈) 등록 (사고 ID 반환)
	 */
	@PostMapping("/Hygiene/Incident")
	public ResponseEntity<Map<String, Object>> saveHygieneIncident(@RequestBody Map<String, Object> payload) {
		return ResponseEntity.ok(Map.of("incident_id", analyticsService.saveHygieneIncident(payload)));
	}

	/*
	 * part : 위생관리 method : saveHygieneAction comment : 위생 사고 조치 항목 등록
	 */
	@PostMapping("/Hygiene/Action")
	public ResponseEntity<Map<String, Object>> saveHygieneAction(@RequestBody Map<String, Object> payload) {
		return ResponseEntity.ok(Map.of("hygiene_action_id", analyticsService.saveHygieneAction(payload)));
	}

	/*
	 * part : 위생관리 method : completeHygieneAction comment : 위생 조치 완료 처리
	 */
	@PatchMapping("/Hygiene/Action")
	public ResponseEntity<Map<String, Object>> completeHygieneAction(@RequestBody Map<String, Object> payload) {
		return ResponseEntity.ok(Map.of("updated", analyticsService.completeHygieneAction(payload)));
	}
}
