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
			try {
				createService(service);
				if (!(meal.get("menus") instanceof List<?> menus) || menus.isEmpty())
					throw new IllegalArgumentException("메뉴가 없습니다.");
				for (Object menuValue : menus) {
					if (!(menuValue instanceof Map<?, ?> menu))
						throw new IllegalArgumentException("메뉴 형식을 확인해주세요.");
					Map<String, Object> detail = new java.util.HashMap<>(service);
					menu.forEach((k, v) -> detail.put(k.toString(), v));
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
			throw new IllegalArgumentException("사용 확정은 끼니별 사용 확정 기능을 이용하세요.");
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
		if (mapper.countCompatibleMenu(b) == 0) {
			throw new IllegalArgumentException("메뉴의 식단 유형이 선택한 끼니의 식단 유형과 다릅니다.");
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
		int pricedIngredientCount = integer(result.get("priced_ingredient_count"));
		if (menuCount == 0)
			return;
		if (recipeMenuCount != menuCount || ingredientCount == 0) {
			throw new IllegalArgumentException("메뉴에 1인분 식재료 레시피가 등록되어 있어야 합니다.");
		}
		if (pricedIngredientCount != ingredientCount) {
			throw new IllegalArgumentException("식재료 원가를 계산할 수 없습니다. 공급상품 연결·판매상태·포장 환산량·현재 가격을 확인해주세요.");
		}

		BigDecimal cost = decimal(result.get("meal_cost_per_person"));
		BigDecimal budget = decimal(result.get("meal_budget_per_person"));
		String source = String.valueOf(result.get("budget_source"));
		if (!"INCLUDED_IN_DIET_PRICE".equals(source) && cost.compareTo(budget) > 0) {
			throw new IllegalArgumentException("1인 원가 " + cost + "원이 거래처 끼니 단가 " + budget + "원을 초과합니다.");
		}
	}

	private int integer(Object value) {
		return value == null ? 0 : Integer.parseInt(value.toString());
	}

	private BigDecimal decimal(Object value) {
		return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
	}

	/*
	 * 고객사 유형별 단가는 DB 뷰 한 곳에서 계산한다. 요양원(1)은 elderly를 본식 수로 나누고 간식은 snack, 산업체(4)는
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
