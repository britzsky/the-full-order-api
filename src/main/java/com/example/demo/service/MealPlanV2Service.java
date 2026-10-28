package com.example.demo.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.MealPlanV2Mapper;

@Service
public class MealPlanV2Service {
	private final MealPlanV2Mapper mapper;
	private final MealUsageService usage;
	private WorkspaceNotificationService notifications;
	private AccountMenuRegistrationService menuImporter;
	@org.springframework.beans.factory.annotation.Autowired
	public void setMenuImporter(AccountMenuRegistrationService menuImporter) { this.menuImporter=menuImporter; }
	@org.springframework.beans.factory.annotation.Autowired
	public void setNotifications(WorkspaceNotificationService notifications) { this.notifications=notifications; }

	public MealPlanV2Service(MealPlanV2Mapper mapper, MealUsageService usage) {
		this.mapper = mapper;
		this.usage = usage;
	}

	public List<Map<String, Object>> plans(Map<String, Object> p) {
		return mapper.plans(p);
	}

	public List<Map<String, Object>> services(Map<String, Object> p) {
		return mapper.services(p);
	}

	public List<Map<String, Object>> details(Map<String, Object> p) {
		for (String key : List.of("date_from", "date_to")) {
			Object value = p.get(key);
			if (value != null && !value.toString().isBlank()) {
				try { java.time.LocalDate.parse(value.toString()); }
				catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("날짜는 YYYY-MM-DD 형식이어야 합니다."); }
			}
		}
		return mapper.details(p);
	}

	public List<Map<String, Object>> costs(Map<String, Object> p) {
		return mapper.costs(p);
	}

	public List<Map<String, Object>> requirements(Map<String, Object> p) {
		return mapper.requirements(p);
	}

	/** Header, services, recipes and costs succeed or roll back together. */
	@Transactional
	public Map<String, Object> saveCompletePlan(Map<String, Object> b) {
		if (!(b.get("meals") instanceof List<?> meals) || meals.isEmpty())
			throw new IllegalArgumentException("저장할 식단 메뉴가 없습니다.");
		BigDecimal servings = decimal(b.get("planned_servings"));
		if (servings.signum() <= 0 || servings.stripTrailingZeros().scale() > 0)
			throw new IllegalArgumentException("예정 식수를 양의 정수로 입력해주세요.");
		createPlan(b);
		for (var existing : mapper.services(b)) usage.cancel(existing);
		for (Object value : meals) {
			if (!(value instanceof Map<?, ?> meal))
				throw new IllegalArgumentException("식단 형식을 확인해주세요.");
			Map<String, Object> service = new java.util.HashMap<>();
			meal.forEach((k, v) -> service.put(k.toString(), v));
			for (String key : List.of("table_id", "account_id", "user_id", "planned_servings"))
				service.put(key, b.get(key));
			service.put("status", "DRAFT");
			markSpecialMeal(service, meal.get("menus"));
			try {
				createService(service);
				if (!(meal.get("menus") instanceof List<?> menus) || menus.isEmpty())
					throw new IllegalArgumentException("메뉴가 없습니다.");
				for (Object menuValue : menus) {
					if (!(menuValue instanceof Map<?, ?> menu))
						throw new IllegalArgumentException("메뉴 형식을 확인해주세요.");
					Map<String, Object> detail = new java.util.HashMap<>(service);
					menu.forEach((k, v) -> detail.put(k.toString(), v));
					// 끼니 값(service.special_yn)이 메뉴로 번지지 않게 메뉴 값만 쓴다
					detail.put("special_yn", "Y".equals(String.valueOf(menu.get("special_yn"))) ? "Y" : "N");
					try {
						insertDetail(detail);
					} catch (IllegalArgumentException e) {
						throw new IllegalArgumentException(detail.get("menu_name") + ": " + e.getMessage());
					}
				}
				recalculate(service);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException(
						service.get("meal_date") + " / " + service.get("meal_slot") + ": " + e.getMessage());
			}
		}
		if (notifications!=null) notifications.mealChanged(String.valueOf(b.get("account_id")),String.valueOf(b.get("table_id")),String.valueOf(b.get("user_id")));
		return ok(b.get("table_id"));
	}

	/**
	 * 특식은 메뉴 하나 단위(2026-10-27 사용자 결정: 나머지는 일반식이고 메뉴 하나가 특식). 끼니 special_yn은 특식 메뉴가 하나라도 있으면 Y,
	 * 메모가 없으면 특식 메뉴 이름을 넣는다(정산 화면 '특식: 11-02 중식(삼계탕)'). 메뉴에 특식 표시가 하나도 없으면 끼니 값 그대로(예전 요청 호환).
	 */
	static void markSpecialMeal(Map<String, Object> service, Object menus) {
		if (!(menus instanceof List<?> list)) return;
		List<String> names = new java.util.ArrayList<>();
		for (Object value : list)
			if (value instanceof Map<?, ?> menu && "Y".equals(String.valueOf(menu.get("special_yn")))) names.add(String.valueOf(menu.get("menu_name")));
		if (names.isEmpty()) return;
		service.put("special_yn", "Y");
		Object note = service.get("special_note");
		if (note == null || note.toString().isBlank()) {
			String joined = String.join(", ", names);
			service.put("special_note", joined.length() > 200 ? joined.substring(0, 200) : joined);
		}
	}

	/**
	 * 식단표는 본사 메뉴로 만든다(2026-10-22). 거래처 메뉴에 아직 없는 본사 메뉴면 레시피째 거래처 메뉴로 복사한다
	 * (필요량·재고 차감·발주는 거래처 메뉴 레시피 기준). 이미 있으면 그대로 쓴다.
	 */
	private void ensureAccountMenu(Map<String, Object> b) {
		if (menuImporter == null || mapper.accountMenuExists(b) > 0)
			return;
		Map<String, Object> request = new java.util.HashMap<>();
		request.put("account_id", b.get("account_id"));
		request.put("menu_id", b.get("menu_id"));
		request.put("user_id", b.get("user_id"));
		menuImporter.importStandard(request);
	}

	public Map<String, Object> createPlan(Map<String, Object> b) {
		req(b, "table_id", "account_id", "table_year", "table_month", "table_week");
		validateMealPlanType(b);
		mapper.insertPlan(b);
		return ok(b.get("table_id"));
	}

	@Transactional
	public Map<String, Object> updatePlan(Map<String, Object> b) {
		req(b, "table_id");
		if ("CANCELED".equals(b.get("status"))) {
			for (var row : mapper.services(b)) usage.cancel(row);
		} else if (b.containsKey("status") && !"DRAFT".equals(b.get("status"))) {
			throw new IllegalArgumentException("사용 확정은 식사별 사용 확정 기능을 이용하세요.");
		}
		validateMealPlanType(b);
		return changed(mapper.updatePlan(b), b.get("table_id"));
	}

	@Transactional
	public Map<String, Object> deletePlan(Map<String, Object> b) {
		req(b, "table_id");
		for (var existing : mapper.services(b)) usage.cancel(existing);
		b.put("status", "CANCELED");
		return changed(mapper.updatePlan(b), b.get("table_id"));
	}

	/*
	 * 날짜·식사구분별 식단을 생성한다. 정산 일관성을 위해 클라이언트의 meal_budget_per_person은 사용하지 않고 고객사 원장에서
	 * 다시 계산한다.
	 */
	@Transactional
	public Map<String, Object> createService(Map<String, Object> b) {
		req(b, "table_id", "account_id", "meal_date", "meal_slot", "meal_slot_code", "planned_servings");
		validateMealPlanType(b);
		applyServerCalculatedBudget(b);
		// Lock and restore before an upsert can replace the old requirements.
		for (var existing : mapper.services(b)) {
			if (String.valueOf(existing.get("meal_date")).equals(String.valueOf(b.get("meal_date")))
					&& String.valueOf(existing.get("meal_slot")).equals(String.valueOf(b.get("meal_slot")))) usage.reopen(existing);
		}
		b.put("status", "DRAFT");
		mapper.insertService(b);
		mapper.deleteRequirements(b);
		mapper.deleteCosts(b);
		mapper.deleteDetailsByService(b);
		return ok(b.get("meal_service_id"));
	}

	@Transactional
	public Map<String, Object> updateService(Map<String, Object> b) {
		req(b, "meal_service_id");
		if ("COMPLETED".equals(b.get("status"))) return usage.complete(b);
		if ("CANCELED".equals(b.get("status"))) return usage.cancel(b);
		usage.reopen(b);
		b.put("status", "DRAFT");
		var stored = new java.util.HashMap<>(usage.lock(b)); stored.putAll(b);
		applyServerCalculatedBudget(stored);
		int count = mapper.updateService(stored);
		recalculate(stored);
		return changed(count, b.get("meal_service_id"));
	}

	@Transactional
	public Map<String, Object> deleteService(Map<String, Object> b) {
		req(b, "meal_service_id");
		return usage.cancel(b);
	}

	/* 메뉴 유형은 식단표 전체가 아니라 실제 날짜·식사 서비스의 식단유형과 비교한다. */
	@Transactional
	public Map<String, Object> createDetail(Map<String, Object> b) {
		Map<String,Object> result = insertDetail(b);
		recalculate(b);
		return result;
	}

	private Map<String,Object> insertDetail(Map<String,Object> b) {
		req(b, "meal_service_id", "table_id", "account_id", "menu_id", "menu_name");
		usage.reopen(b);
		ensureAccountMenu(b);
		if (mapper.countCompatibleMenu(b) == 0) {
			throw new IllegalArgumentException("메뉴의 식단 유형이 선택한 식사의 식단 유형과 다릅니다.");
		}
		mapper.insertDetail(b);
		validateServiceCost(b);
		return ok(b.get("meal_detail_id"));
	}

	@Transactional
	public Map<String, Object> updateDetail(Map<String, Object> b) {
		req(b, "meal_detail_id");
		var original = mapper.details(b);
		if (original.isEmpty()) throw new IllegalArgumentException("식단 메뉴를 찾을 수 없습니다.");
		b.put("meal_service_id", original.get(0).get("meal_service_id"));
		b.put("account_id", original.get(0).get("account_id"));
		usage.reopen(b);
		if (b.get("menu_id") != null) {
			req(b, "meal_service_id", "account_id");
			ensureAccountMenu(b);
			if (mapper.countCompatibleMenu(b) == 0) {
				throw new IllegalArgumentException("The menu meal_plan_type does not match the meal service.");
			}
		}
		int changed = mapper.updateDetail(b);
		if (changed == 0)
			throw new IllegalArgumentException("Target row was not found.");
		Map<String, Object> validationParam = new java.util.HashMap<>(b);
		if (validationParam.get("meal_service_id") == null) {
			List<Map<String, Object>> rows = mapper.details(b);
			if (rows.isEmpty())
				throw new IllegalArgumentException("Meal detail was not found.");
			validationParam.put("meal_service_id", rows.get(0).get("meal_service_id"));
		}
		validateServiceCost(validationParam);
		recalculate(validationParam);
		return ok(b.get("meal_detail_id"));
	}

	@Transactional
	public Map<String, Object> deleteDetail(Map<String, Object> b) {
		req(b, "meal_detail_id");
		var rows = mapper.details(b);
		if(rows.isEmpty()) throw new IllegalArgumentException("식단 메뉴를 찾을 수 없습니다.");
		var p = new java.util.HashMap<>(rows.get(0));
		usage.reopen(p);
		int count = mapper.deleteDetail(b);
		recalculate(p);
		return changed(count, b.get("meal_detail_id"));
	}

	@Transactional
	public Map<String, Object> recalculate(Map<String, Object> b) {
		req(b, "meal_service_id", "account_id");
		usage.reopen(b);
		validateServiceCost(b);
		mapper.deleteRequirements(b);
		mapper.deleteCosts(b);
		int requirements = mapper.insertRequirements(b);
		int costs = mapper.insertCosts(b);
		usage.snapshot(b);
		return Map.of("code", 200, "message", "success", "requirement_count", requirements, "menu_cost_count", costs);
	}

	private void validateServiceCost(Map<String, Object> b) {
		req(b, "meal_service_id");
		Map<String, Object> result = mapper.selectServiceCostValidation(b);
		if (result == null)
			throw new IllegalArgumentException("Meal service was not found.");

		int menuCount = integer(result.get("menu_count"));
		int recipeMenuCount = integer(result.get("recipe_menu_count"));
		int ingredientCount = integer(result.get("ingredient_count"));
		if (menuCount == 0)
			return;
		if (recipeMenuCount != menuCount || ingredientCount == 0) {
			throw new IllegalArgumentException("메뉴에 1인분 식재료 레시피가 등록되어 있어야 합니다.");
		}
		// 레시피 단위를 식재료 기준단위로 바꿀 수 없으면 필요량(재고 차감·발주)을 계산할 수 없으므로 막는다.
		if (integer(result.get("unit_problem_count")) > 0) {
			throw new IllegalArgumentException("레시피 단위를 식재료 기준단위로 바꿀 수 없습니다: " + result.get("unit_problem_names")
					+ " · 메뉴 레시피의 단위 확인 요망");
		}
		// 단가 미등록·식단가 초과는 막지 않는다(2026-10-22 결정). 주간 정산(budgetSummary)에서 경고로 보여준다.
	}

	/**
	 * 주간 식단가 정산(2026-10-22 사용자 결정). 끼니별로 식단가를 넘어도 막지 않고, 주 합계로 맞추는지 보여준다(넘어도 경고만).
	 * 특식 메뉴 때문에 넘은 금액(초과분 중 특식 메뉴 원가까지)은 주 합계에서 빼고 '특식 초과'로 따로 보여준다. 금액은 1인 기준이다.
	 * 특식 메뉴 원가가 없는 특식 끼니(메뉴별 특식 전 데이터)는 예전처럼 초과분 전부를 뺀다.
	 */
	public Map<String, Object> budgetSummary(Map<String, Object> p) {
		req(p, "table_id");
		List<Map<String, Object>> rows = mapper.budgetRows(p);
		BigDecimal budget = BigDecimal.ZERO, cost = BigDecimal.ZERO, specialOver = BigDecimal.ZERO;
		int unpriced = 0;
		List<Map<String, Object>> meals = new java.util.ArrayList<>();
		java.util.Map<String, Map<String, Object>> days = new java.util.LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			Map<String, Object> meal = new java.util.LinkedHashMap<>(row);
			BigDecimal mealBudget = decimal(row.get("meal_budget_per_person"));
			BigDecimal mealCost = decimal(row.get("meal_cost_per_person"));
			BigDecimal diff = mealCost.subtract(mealBudget);
			boolean special = "Y".equals(String.valueOf(row.get("special_yn")));
			boolean includedInDietPrice = "INCLUDED_IN_DIET_PRICE".equals(String.valueOf(row.get("budget_source")));
			BigDecimal over = includedInDietPrice ? BigDecimal.ZERO : diff.max(BigDecimal.ZERO);
			meal.put("difference", diff);
			meal.put("over_budget", over.signum() > 0);
			if (special && over.signum() > 0) {
				BigDecimal specialCost = decimal(row.get("special_cost_per_person"));
				// 일반 메뉴 때문에 넘은 금액은 그대로 초과로 남긴다
				BigDecimal specialPart = specialCost.signum() > 0 ? over.min(specialCost) : over;
				specialOver = specialOver.add(specialPart);
				mealCost = mealCost.subtract(specialPart);
			}
			budget = budget.add(mealBudget);
			cost = cost.add(mealCost);
			unpriced += integer(row.get("unpriced_ingredient_count"));
			meals.add(meal);
			Map<String, Object> day = days.computeIfAbsent(String.valueOf(row.get("meal_date")), date -> {
				Map<String, Object> value = new java.util.LinkedHashMap<>();
				value.put("meal_date", date);
				value.put("weekday", row.get("weekday"));
				value.put("budget", BigDecimal.ZERO);
				value.put("cost", BigDecimal.ZERO);
				return value;
			});
			day.put("budget", decimal(day.get("budget")).add(mealBudget));
			day.put("cost", decimal(day.get("cost")).add(decimal(row.get("meal_cost_per_person"))));
		}
		BigDecimal balance = budget.subtract(cost);
		Map<String, Object> result = new java.util.LinkedHashMap<>();
		result.put("meals", meals);
		result.put("days", new java.util.ArrayList<>(days.values()));
		result.put("budget_total", budget);
		result.put("cost_total", cost);
		result.put("special_over_total", specialOver);
		result.put("balance", balance);
		result.put("over_budget", balance.signum() < 0);
		result.put("unpriced_ingredient_count", unpriced);
		String message = balance.signum() < 0
				? "이번 주 식단가 합계를 " + balance.negate().setScale(0, java.math.RoundingMode.HALF_UP) + "원 넘었습니다 · 다른 식사에서 조절 요망"
				: "이번 주 식단가 안에서 " + balance.setScale(0, java.math.RoundingMode.HALF_UP) + "원 남았습니다";
		if (specialOver.signum() > 0)
			message += " (특식 초과 " + specialOver.setScale(0, java.math.RoundingMode.HALF_UP) + "원 별도)";
		if (unpriced > 0)
			message += " · 단가 미등록 식재료 " + unpriced + "건은 원가에 빠져 있음";
		result.put("message", message);
		return result;
	}

	private int integer(Object value) {
		return value == null ? 0 : Integer.parseInt(value.toString());
	}

	private BigDecimal decimal(Object value) {
		return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
	}

	/*
	 * 고객사 유형별 단가는 DB 뷰 한 곳에서 계산한다. 요양원(1)은 본식마다 elderly(1끼 단가)와 간식은 snack, 산업체(4)는
	 * 본식마다 diet_price, 학교(5)는 diet_price를 본식 수로 나누며 간식·후식은 본식 총액에 포함한다.
	 */
	private void applyServerCalculatedBudget(Map<String, Object> b) {
		Map<String, Object> budget = mapper.selectMealBudget(b);
		if (budget == null)
			throw new IllegalArgumentException("거래처의 식사구분·단가 설정이 없습니다.");
		Object source = budget.get("budget_source");
		Object amount = budget.get("budget_per_person");
		if (amount == null && "INCLUDED_IN_DIET_PRICE".equals(String.valueOf(source))) {
			// 학교 간식은 이미 본식 단가에 포함되므로 별도 예산을 가산하지 않는다.
			amount = BigDecimal.ZERO;
		}
		if (amount == null)
			throw new IllegalArgumentException("이 거래처 유형과 식사구분의 단가가 설정되지 않았습니다.");
		BigDecimal calculated = new BigDecimal(amount.toString());
		if (calculated.signum() < 0)
			throw new IllegalArgumentException("Calculated meal budget cannot be negative.");
		b.put("meal_budget_per_person", calculated);
		b.put("budget_source", source);
		b.put("budget_source_amount", budget.get("budget_source_amount"));
		b.put("budget_divisor", budget.get("budget_divisor"));
	}

	private void req(Map<String, Object> b, String... fields) {
		for (String field : fields) {
			if (b.get(field) == null || b.get(field).toString().isBlank()) {
				throw new IllegalArgumentException(field + " is required.");
			}
		}
	}

	private void validateMealPlanType(Map<String, Object> b) {
		Object value = b.get("meal_plan_type");
		if (value == null || value.toString().isBlank())
			return;
		int type = Integer.parseInt(value.toString());
		if (type < 0 || type > 5)
			throw new IllegalArgumentException("meal_plan_type must be between 0 and 5.");
	}

	private Map<String, Object> ok(Object id) {
		return Map.of("code", 200, "message", "success", "id", id);
	}

	private Map<String, Object> changed(int n, Object id) {
		if (n == 0)
			throw new IllegalArgumentException("Target row was not found.");
		return ok(id);
	}
}
