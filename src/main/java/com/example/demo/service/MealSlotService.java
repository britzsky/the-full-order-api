package com.example.demo.service;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.MealSlotMapper;

/**
 * 본사 끼니 종류 관리(2026-10-23 사용자 결정: 본사가 끼니 종류를 추가). 본식(MAIN)/간식(SNACK) 구분이 단가 뷰
 * vw_account_meal_slot_budget·금액대 템플릿 1일 식단가·자동 식단 기본 분류를 정한다. 거래처가 쓰고 있을 수 있어 삭제는 없고
 * '사용 안 함'(새로 고를 수 없음, 이미 고른 거래처에는 그대로 보임)만 둔다. 코드는 9부터 자동(최대 99).
 */
@Service
public class MealSlotService {
	private static final Set<String> KINDS = Set.of("MAIN", "SNACK");
	private final MealSlotMapper mapper;

	public MealSlotService(MealSlotMapper mapper) {
		this.mapper = mapper;
	}

	public List<Map<String, Object>> list() {
		return mapper.list();
	}

	@Transactional
	public Map<String, Object> create(Map<String, Object> body) {
		Map<String, Object> row = values(body);
		int code = mapper.nextCode();
		if (code > 99)
			throw new IllegalArgumentException("끼니 종류는 코드 99까지만 만들 수 있습니다.");
		row.put("meal_slot_code", code);
		checkName(row);
		row.put("display_order", Math.min(mapper.nextOrder(), 127));
		mapper.insert(row);
		return Map.of("code", 200, "message", "success", "meal_slot_code", code);
	}

	@Transactional
	public Map<String, Object> update(Map<String, Object> body) {
		Map<String, Object> row = values(body);
		row.put("meal_slot_code", code(body.get("meal_slot_code")));
		if (mapper.exists(row) == 0)
			throw new IllegalArgumentException("끼니 종류를 찾을 수 없습니다.");
		String active = Objects.toString(body.get("active_yn"), "Y");
		if (!Set.of("Y", "N").contains(active))
			throw new IllegalArgumentException("사용 여부는 Y/N입니다.");
		row.put("active_yn", active);
		checkName(row);
		mapper.update(row);
		return Map.of("code", 200, "message", "success");
	}

	/** 기본 표시 순서: meal_slot_codes 순서대로 0,1,2… (빠진 끼니는 뒤로) */
	@Transactional
	public Map<String, Object> reorder(Map<String, Object> body) {
		if (!(body.get("meal_slot_codes") instanceof List<?> codes) || codes.isEmpty())
			throw new IllegalArgumentException("끼니 순서를 보내주세요.");
		List<Integer> order = new ArrayList<>();
		for (Object value : codes) {
			int code = code(value);
			if (!order.contains(code)) order.add(code);
		}
		for (Map<String, Object> slot : mapper.list()) {
			int code = code(slot.get("meal_slot_code"));
			if (!order.contains(code)) order.add(code);
		}
		for (int i = 0; i < order.size(); i++) {
			Map<String, Object> row = new HashMap<>();
			row.put("meal_slot_code", order.get(i));
			row.put("display_order", Math.min(i, 127));
			row.put("user_id", body.get("user_id"));
			mapper.updateOrder(row);
		}
		return Map.of("code", 200, "message", "success");
	}

	private Map<String, Object> values(Map<String, Object> body) {
		String name = Objects.toString(body.get("meal_slot_name"), "").trim();
		if (name.isEmpty() || name.length() > 30)
			throw new IllegalArgumentException("끼니 이름은 1~30자로 입력해주세요.");
		String kind = Objects.toString(body.get("slot_kind"), "SNACK");
		if (!KINDS.contains(kind))
			throw new IllegalArgumentException("본식(MAIN) 또는 간식(SNACK)을 골라주세요.");
		Map<String, Object> row = new HashMap<>();
		row.put("meal_slot_name", name);
		row.put("slot_kind", kind);
		row.put("user_id", body.get("user_id"));
		return row;
	}

	private void checkName(Map<String, Object> row) {
		if (mapper.sameName(row) > 0)
			throw new IllegalArgumentException("같은 이름의 끼니가 이미 있습니다: " + row.get("meal_slot_name"));
	}

	private static int code(Object value) {
		try {
			int code = Integer.parseInt(Objects.toString(value, "").trim());
			if (code < 0 || code > 99) throw new NumberFormatException();
			return code;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("끼니 코드를 확인해주세요: " + value);
		}
	}
}
