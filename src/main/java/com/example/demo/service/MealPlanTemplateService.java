package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.MealPlanTemplateMapper;

/**
 * 본사 금액대 식단표 템플릿(2026-10-22 사용자 결정).
 * 금액대 = 1일 식단가(조·중·석 합계)를 1,000원 단위로 올린 값: 12,001~13,000원 → 13,000원 템플릿.
 * 금액대·식단유형별 최대 3개(순번 1~3). 불러올 때는 거래처 1일 식단가와 같은 금액대만 보여준다.
 * 메뉴는 본사 메뉴이고, 불러온 식단을 저장하면 MealPlanV2Service가 거래처 메뉴로 복사한다.
 */
@Service
public class MealPlanTemplateService {
	static final int MAX_DAYS = 7;
	private final MealPlanTemplateMapper mapper;

	public MealPlanTemplateService(MealPlanTemplateMapper mapper) {
		this.mapper = mapper;
	}

	/** 1일 식단가 → 금액대(1,000원 단위 올림). 0 이하면 null */
	static Integer priceTier(BigDecimal dailyBudget) {
		if (dailyBudget == null || dailyBudget.signum() <= 0)
			return null;
		return dailyBudget.divide(BigDecimal.valueOf(1000), 0, RoundingMode.CEILING).intValueExact() * 1000;
	}

	public List<Map<String, Object>> list(Map<String, Object> p) {
		return mapper.templates(p);
	}

	public Map<String, Object> detail(Map<String, Object> p) {
		require(p, "meal_plan_template_id");
		List<Map<String, Object>> rows = mapper.templates(Map.of("meal_plan_template_id", p.get("meal_plan_template_id")));
		if (rows.isEmpty())
			throw new IllegalArgumentException("템플릿을 찾을 수 없습니다.");
		Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
		result.put("items", mapper.items(p));
		return result;
	}

	/** 거래처 1일 식단가와 같은 금액대의 템플릿(식단유형을 주면 그 유형만). */
	public Map<String, Object> forAccount(Map<String, Object> p) {
		require(p, "account_id");
		Map<String, Object> budget = mapper.accountDailyBudget(p);
		BigDecimal daily = budget == null || budget.get("daily_budget") == null ? null : new BigDecimal(budget.get("daily_budget").toString());
		Integer tier = priceTier(daily);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("daily_budget", daily);
		result.put("budget_breakdown", budget == null ? null : budget.get("breakdown"));
		result.put("price_tier", tier);
		if (tier == null) {
			result.put("templates", List.of());
			result.put("message", "거래처 1일 식단가(조·중·석식 단가)가 설정되지 않아 금액대를 정할 수 없습니다.");
			return result;
		}
		Map<String, Object> query = new HashMap<>();
		query.put("price_tier", tier);
		query.put("meal_plan_type", p.get("meal_plan_type"));
		query.put("active_only", "Y");
		result.put("templates", mapper.templates(query));
		return result;
	}

	/** 템플릿 저장(새로 만들기·수정). items는 통째로 바꾼다. */
	@Transactional
	public Map<String, Object> save(Map<String, Object> b) {
		require(b, "price_tier", "meal_plan_type", "template_seq", "template_name");
		int tier = integer(b.get("price_tier"), "금액대");
		if (tier <= 0 || tier % 1000 != 0)
			throw new IllegalArgumentException("금액대는 1,000원 단위로 입력해주세요.");
		int type = integer(b.get("meal_plan_type"), "식단유형");
		if (type < 0 || type > 5)
			throw new IllegalArgumentException("식단유형을 확인해주세요.");
		int seq = integer(b.get("template_seq"), "순번");
		if (seq < 1 || seq > 3)
			throw new IllegalArgumentException("같은 금액대·식단유형의 템플릿은 3개(순번 1~3)까지입니다.");
		String name = String.valueOf(b.get("template_name")).trim();
		if (name.isEmpty() || name.length() > 100)
			throw new IllegalArgumentException("템플릿 이름은 1~100자로 입력해주세요.");
		b.put("template_name", name);
		if (!(b.get("items") instanceof List<?> items) || items.isEmpty())
			throw new IllegalArgumentException("템플릿에 메뉴를 넣어주세요.");
		List<Map<String, Object>> rows = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Map<String, Integer> order = new HashMap<>();
		for (Object value : items) {
			if (!(value instanceof Map<?, ?> item))
				throw new IllegalArgumentException("템플릿 메뉴 형식을 확인해주세요.");
			int day = integer(item.get("day_index"), "요일");
			int slot = integer(item.get("meal_slot_code"), "식사구분");
			String menu = Objects.toString(item.get("menu_id"), "").trim();
			if (day < 1 || day > MAX_DAYS)
				throw new IllegalArgumentException("요일은 1(월)~7(일)입니다.");
			if (slot < 0 || slot > 8 || menu.isEmpty())
				throw new IllegalArgumentException("식사구분·메뉴를 확인해주세요.");
			if (!seen.add(day + "|" + slot + "|" + menu))
				continue;
			if (mapper.activeMenuCount(Map.of("menu_id", menu)) == 0)
				throw new IllegalArgumentException("본사 메뉴를 찾을 수 없습니다: " + menu);
			String key = day + "|" + slot;
			int sort = order.merge(key, 1, Integer::sum);
			Map<String, Object> row = new HashMap<>();
			row.put("day_index", day);
			row.put("meal_slot_code", slot);
			row.put("sort_order", sort);
			row.put("menu_id", menu);
			row.put("user_id", b.get("user_id"));
			rows.add(row);
		}
		b.put("price_tier", tier);
		b.put("meal_plan_type", type);
		b.put("template_seq", seq);
		try {
			if (b.get("meal_plan_template_id") == null || b.get("meal_plan_template_id").toString().isBlank()) {
				mapper.insertTemplate(b);
			} else if (mapper.updateTemplate(b) == 0) {
				throw new IllegalArgumentException("템플릿을 찾을 수 없습니다.");
			}
		} catch (DuplicateKeyException e) {
			throw new IllegalArgumentException(String.format("%,d원 금액대의 같은 식단유형에 순번 %d 템플릿이 이미 있습니다.", tier, seq));
		}
		mapper.deleteItems(b);
		for (Map<String, Object> row : rows) {
			row.put("meal_plan_template_id", b.get("meal_plan_template_id"));
			mapper.insertItem(row);
		}
		return Map.of("code", 200, "message", "템플릿을 저장했습니다.", "meal_plan_template_id", b.get("meal_plan_template_id"),
				"item_count", rows.size());
	}

	@Transactional
	public Map<String, Object> delete(Map<String, Object> b) {
		require(b, "meal_plan_template_id");
		mapper.deleteItems(b);
		if (mapper.deleteTemplate(b) == 0)
			throw new IllegalArgumentException("템플릿을 찾을 수 없습니다.");
		return Map.of("code", 200, "message", "템플릿을 삭제했습니다.");
	}

	private static int integer(Object value, String label) {
		try {
			return Integer.parseInt(Objects.toString(value, "").trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(label + "을(를) 확인해주세요.");
		}
	}

	private static void require(Map<String, Object> b, String... fields) {
		for (String field : fields)
			if (b.get(field) == null || b.get(field).toString().isBlank())
				throw new IllegalArgumentException(field + " is required.");
	}
}
