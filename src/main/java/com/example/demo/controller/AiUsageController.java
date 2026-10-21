package com.example.demo.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.mapper.AiUsageMapper;

/** AI(OpenAI) 토큰 사용 이력 조회. 본사 전용(WorkspaceAccess) */
@RestController
@RequestMapping("/v2/ai-usage")
public class AiUsageController {
	private final AiUsageMapper mapper;
	private final String dailyLimit;
	private final String inputPrice;
	private final String cachedInputPrice;
	private final String outputPrice;

	public AiUsageController(AiUsageMapper mapper, @Value("${openai.daily-limit-usd:5}") String dailyLimit,
			@Value("${openai.price.input-per-1m:0.75}") String inputPrice,
			@Value("${openai.price.cached-input-per-1m:0.075}") String cachedInputPrice,
			@Value("${openai.price.output-per-1m:4.50}") String outputPrice) {
		this.mapper = mapper;
		this.dailyLimit = dailyLimit;
		this.inputPrice = inputPrice;
		this.cachedInputPrice = cachedInputPrice;
		this.outputPrice = outputPrice;
	}

	/*
	 * part : AI 사용 이력 method : list comment : 기간(from~to, 기본 이번 달)·거래처별 호출 이력(최신 500건)과 기능별 합계, 오늘 사용액·하루 한도·적용 단가
	 */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(value = "from", required = false) String from,
			@RequestParam(value = "to", required = false) String to,
			@RequestParam(value = "account_id", required = false) String accountId) {
		LocalDate end, start;
		try {
			end = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
			start = from == null || from.isBlank() ? end.withDayOfMonth(1) : LocalDate.parse(from);
		} catch (DateTimeParseException e) {
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "기간은 YYYY-MM-DD 형식이어야 합니다."));
		}
		if (start.isAfter(end))
			return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "시작일이 종료일보다 늦습니다."));
		Map<String, Object> p = new HashMap<>();
		p.put("from", start.toString());
		p.put("to", end.toString());
		p.put("account_id", accountId);
		return ResponseEntity.ok(Map.of(
				"from", start.toString(), "to", end.toString(),
				"rows", mapper.list(p),
				"summary", mapper.summary(p),
				"today_cost_usd", mapper.todayCost(),
				"daily_limit_usd", dailyLimit,
				"price_per_1m", Map.of("input", inputPrice, "cached_input", cachedInputPrice, "output", outputPrice)));
	}
}
