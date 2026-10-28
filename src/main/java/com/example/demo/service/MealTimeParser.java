package com.example.demo.service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ERP 거래처 끼니 시간 글자(breakfast_time·lunch_time·dinner_time·snack_time) 해석.
 * 규칙 원본은 docs/MEAL_DOCUMENTS_RULES.md 2절 — 규칙을 바꾸면 그 문서와 MealTimeParserTest 예시를 같이 고친다.
 */
public final class MealTimeParser {
	/** 끼니 코드(tb_meal_slot_master 기본값): 조식 0, 오전간식 1, 중식 2, 오후간식 3, 석식 5, 저녁간식 6 */
	public static final int BREAKFAST = 0, MORNING_SNACK = 1, LUNCH = 2, AFTERNOON_SNACK = 3, DINNER = 5, EVENING_SNACK = 6;

	private static final Pattern SPLIT = Pattern.compile("[/\\n]+|(?=\\*)");
	private static final Pattern TIME = Pattern.compile("(?:(오전|오후|am|pm)\\s*)?(\\d{1,2})\\s*(?::|시)\\s*(\\d{2})?\\s*분?", Pattern.CASE_INSENSITIVE);
	private static final Pattern NOTE = Pattern.compile("\\(([^)]*)\\)");

	/** 조각 하나: 시간(못 찾으면 null)·대상 이름·괄호 메모·원래 글자 */
	public record Part(LocalTime time, String label, String note, String raw) {}

	/** 끼니 하나에 정한 결과. time이 null이면 text(원래 글자)를 그대로 쓴다 */
	public record SlotTime(LocalTime time, String text, String note) {}

	private MealTimeParser() {}

	/** ERP 네 칸 → 끼니 코드별 시간. 간식은 시간으로 오전(11시 전)·오후(16시 전)·저녁간식으로 나눈다 */
	public static Map<Integer, SlotTime> resolve(String breakfast, String lunch, String dinner, String snack) {
		Map<Integer, SlotTime> result = new LinkedHashMap<>();
		put(result, BREAKFAST, pick(parse(breakfast, BREAKFAST)));
		put(result, LUNCH, pick(parse(lunch, LUNCH)));
		put(result, DINNER, pick(parse(dinner, DINNER)));
		Map<Integer, List<Part>> snacks = new LinkedHashMap<>();
		for (Part part : parse(snack, MORNING_SNACK)) {
			// 시간 없는 간식 조각은 어느 간식인지 알 수 없어 쓰지 않는다
			if (part.time() == null) continue;
			snacks.computeIfAbsent(snackSlot(part.time()), k -> new ArrayList<>()).add(part);
		}
		snacks.forEach((code, parts) -> put(result, code, pick(parts)));
		return result;
	}

	static int snackSlot(LocalTime time) {
		if (time.isBefore(LocalTime.of(11, 0))) return MORNING_SNACK;
		if (time.isBefore(LocalTime.of(16, 0))) return AFTERNOON_SNACK;
		return EVENING_SNACK;
	}

	/** slot: 오전·오후가 안 적혔을 때 판단용(석식은 12시 전이면 오후, 중식·간식은 7시 전이면 오후) */
	public static List<Part> parse(String raw, int slot) {
		List<Part> parts = new ArrayList<>();
		if (raw == null) return parts;
		for (String piece : SPLIT.split(raw)) {
			String text = piece.trim();
			while (text.startsWith("*")) text = text.substring(1).trim();
			if (text.isEmpty()) continue;
			Matcher m = TIME.matcher(text);
			if (!m.find()) {
				parts.add(new Part(null, text, null, text));
				continue;
			}
			int hour = Integer.parseInt(m.group(2));
			int minute = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
			String ampm = m.group(1) == null ? "" : m.group(1).toLowerCase();
			if ((ampm.equals("오후") || ampm.equals("pm")) && hour < 12) hour += 12;
			else if (ampm.isEmpty() && slot == DINNER && hour < 12) hour += 12;
			else if (ampm.isEmpty() && slot != BREAKFAST && slot != DINNER && hour < 7) hour += 12;
			if (hour > 23 || minute > 59) {
				parts.add(new Part(null, text, null, text));
				continue;
			}
			String rest = (text.substring(0, m.start()) + text.substring(m.end())).trim();
			Matcher noteMatcher = NOTE.matcher(rest);
			String note = noteMatcher.find() ? noteMatcher.group(1).trim() : null;
			String label = NOTE.matcher(rest).replaceAll("").replaceAll("^[\\s:\\-]+|[\\s:\\-]+$", "");
			parts.add(new Part(LocalTime.of(hour, minute), label, note == null || note.isEmpty() ? null : note, text));
		}
		return parts;
	}

	/** 한 끼에 여러 개면: '요양원' → '주간보호'가 아닌 것 → 그중 가장 이른 시간. 시간이 하나도 없으면 원래 글자 */
	static SlotTime pick(List<Part> parts) {
		List<Part> timed = parts.stream().filter(p -> p.time() != null).toList();
		if (timed.isEmpty()) {
			String text = String.join(" / ", parts.stream().map(Part::raw).toList());
			return text.isEmpty() ? null : new SlotTime(null, text, null);
		}
		List<Part> candidates = timed.stream().filter(p -> p.label().contains("요양원")).toList();
		if (candidates.isEmpty()) candidates = timed.stream().filter(p -> !p.label().contains("주간보호")).toList();
		if (candidates.isEmpty()) candidates = timed;
		Part chosen = candidates.stream().min(Comparator.comparing(Part::time)).orElseThrow();
		return new SlotTime(chosen.time(), chosen.raw(), chosen.note());
	}

	private static void put(Map<Integer, SlotTime> result, int code, SlotTime time) {
		if (time != null) result.put(code, time);
	}
}
