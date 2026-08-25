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

	public MealPlanV2Service(MealPlanV2Mapper mapper) { this.mapper = mapper; }

	public List<Map<String, Object>> plans(Map<String, Object> p) { return mapper.plans(p); }
	public List<Map<String, Object>> services(Map<String, Object> p) { return mapper.services(p); }
	public List<Map<String, Object>> details(Map<String, Object> p) { return mapper.details(p); }
	public List<Map<String, Object>> costs(Map<String, Object> p) { return mapper.costs(p); }
	public List<Map<String, Object>> requirements(Map<String, Object> p) { return mapper.requirements(p); }

	public Map<String, Object> createPlan(Map<String, Object> b) {
		req(b, "table_id", "account_id", "table_year", "table_month", "table_week");
		validateMealPlanType(b);
		mapper.insertPlan(b);
		return ok(b.get("table_id"));
	}

	public Map<String, Object> updatePlan(Map<String, Object> b) {
		req(b, "table_id");
		validateMealPlanType(b);
		return changed(mapper.updatePlan(b), b.get("table_id"));
	}

	public Map<String, Object> deletePlan(Map<String, Object> b) {
		req(b, "table_id");
		if (!mapper.services(b).isEmpty()) throw new IllegalArgumentException("Delete meal services first.");
		return changed(mapper.deletePlan(b), b.get("table_id"));
	}

	/*
	 * 날짜·식사구분별 식단을 생성한다.
	 * 정산 일관성을 위해 클라이언트의 meal_budget_per_person은 사용하지 않고 고객사 원장에서 다시 계산한다.
	 */
	@Transactional
	public Map<String, Object> createService(Map<String, Object> b) {
		req(b, "table_id", "account_id", "meal_date", "meal_slot", "meal_slot_code", "planned_servings");
		validateMealPlanType(b);
		applyServerCalculatedBudget(b);
		mapper.insertService(b);
		mapper.deleteRequirements(b);
		mapper.deleteCosts(b);
		mapper.deleteDetailsByService(b);
		return ok(b.get("meal_service_id"));
	}

	public Map<String, Object> updateService(Map<String, Object> b) {
		req(b, "meal_service_id");
		return changed(mapper.updateService(b), b.get("meal_service_id"));
	}

	public Map<String, Object> deleteService(Map<String, Object> b) {
		req(b, "meal_service_id");
		if (!mapper.details(b).isEmpty()) throw new IllegalArgumentException("Delete meal details first.");
		return changed(mapper.deleteService(b), b.get("meal_service_id"));
	}

	/* 메뉴 유형은 식단표 전체가 아니라 실제 날짜·식사 서비스의 식단유형과 비교한다. */
	@Transactional
	public Map<String, Object> createDetail(Map<String, Object> b) {
		req(b, "meal_service_id", "table_id", "account_id", "menu_id", "menu_name");
		if (mapper.countCompatibleMenu(b) == 0) {
			throw new IllegalArgumentException("The menu meal_plan_type does not match the meal service.");
		}
		mapper.insertDetail(b);
		validateServiceCost(b);
		return ok(b.get("meal_detail_id"));
	}

	@Transactional
	public Map<String, Object> updateDetail(Map<String, Object> b) {
		req(b, "meal_detail_id");
		if (b.get("menu_id") != null) {
			req(b, "meal_service_id", "account_id");
			if (mapper.countCompatibleMenu(b) == 0) {
				throw new IllegalArgumentException("The menu meal_plan_type does not match the meal service.");
			}
		}
		int changed = mapper.updateDetail(b);
		if (changed == 0) throw new IllegalArgumentException("Target row was not found.");
		Map<String, Object> validationParam = new java.util.HashMap<>(b);
		if (validationParam.get("meal_service_id") == null) {
			List<Map<String, Object>> rows = mapper.details(b);
			if (rows.isEmpty()) throw new IllegalArgumentException("Meal detail was not found.");
			validationParam.put("meal_service_id", rows.get(0).get("meal_service_id"));
		}
		validateServiceCost(validationParam);
		return ok(b.get("meal_detail_id"));
	}

	public Map<String, Object> deleteDetail(Map<String, Object> b) {
		req(b, "meal_detail_id");
		return changed(mapper.deleteDetail(b), b.get("meal_detail_id"));
	}

	@Transactional
	public Map<String, Object> recalculate(Map<String, Object> b) {
		req(b, "meal_service_id", "account_id");
		validateServiceCost(b);
		mapper.deleteRequirements(b);
		mapper.deleteCosts(b);
		int requirements = mapper.insertRequirements(b);
		int costs = mapper.insertCosts(b);
		return Map.of("code", 200, "message", "success", "requirement_count", requirements, "menu_cost_count", costs);
	}

	private void validateServiceCost(Map<String, Object> b) {
		req(b, "meal_service_id");
		Map<String, Object> result = mapper.selectServiceCostValidation(b);
		if (result == null) throw new IllegalArgumentException("Meal service was not found.");

		int menuCount = integer(result.get("menu_count"));
		int recipeMenuCount = integer(result.get("recipe_menu_count"));
		int ingredientCount = integer(result.get("ingredient_count"));
		int pricedIngredientCount = integer(result.get("priced_ingredient_count"));
		if (menuCount == 0) return;
		if (recipeMenuCount != menuCount || ingredientCount == 0) {
			throw new IllegalArgumentException("Every menu must have a per-person ingredient recipe before it can be added to a meal plan.");
		}
		if (pricedIngredientCount != ingredientCount) {
			throw new IllegalArgumentException("Every recipe ingredient must have an active supplier product, a positive base quantity, and a current purchase price.");
		}

		BigDecimal cost = decimal(result.get("meal_cost_per_person"));
		BigDecimal budget = decimal(result.get("meal_budget_per_person"));
		String source = String.valueOf(result.get("budget_source"));
		if (!"INCLUDED_IN_DIET_PRICE".equals(source) && cost.compareTo(budget) > 0) {
			throw new IllegalArgumentException("Meal cost per person (" + cost + ") exceeds the customer budget (" + budget + ").");
		}
	}

	private int integer(Object value) { return value == null ? 0 : Integer.parseInt(value.toString()); }
	private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }

	/*
	 * 고객사 유형별 단가는 DB 뷰 한 곳에서 계산한다.
	 * 요양원(1)은 elderly를 본식 수로 나누고 간식은 snack, 산업체(4)는 본식마다 diet_price,
	 * 학교(5)는 diet_price를 본식 수로 나누며 간식·후식은 본식 총액에 포함한다.
	 */
	private void applyServerCalculatedBudget(Map<String, Object> b) {
		Map<String, Object> budget = mapper.selectMealBudget(b);
		if (budget == null) throw new IllegalArgumentException("Customer meal budget configuration was not found.");
		Object source = budget.get("budget_source");
		Object amount = budget.get("budget_per_person");
		if (amount == null && "INCLUDED_IN_DIET_PRICE".equals(String.valueOf(source))) {
			// 학교 간식은 이미 본식 단가에 포함되므로 별도 예산을 가산하지 않는다.
			amount = BigDecimal.ZERO;
		}
		if (amount == null) throw new IllegalArgumentException("Meal budget is missing for this account type and meal slot.");
		BigDecimal calculated = new BigDecimal(amount.toString());
		if (calculated.signum() < 0) throw new IllegalArgumentException("Calculated meal budget cannot be negative.");
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
		if (value == null || value.toString().isBlank()) return;
		int type = Integer.parseInt(value.toString());
		if (type < 0 || type > 5) throw new IllegalArgumentException("meal_plan_type must be between 0 and 5.");
	}

	private Map<String, Object> ok(Object id) { return Map.of("code", 200, "message", "success", "id", id); }
	private Map<String, Object> changed(int n, Object id) {
		if (n == 0) throw new IllegalArgumentException("Target row was not found.");
		return ok(id);
	}
}
