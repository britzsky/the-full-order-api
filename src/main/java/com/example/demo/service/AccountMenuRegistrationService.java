package com.example.demo.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.AccountMenuRecipeMapper;
import com.example.demo.mapper.AccountMenuRegistrationMapper;

/**
 * 거래처 메뉴를 식단표 PDF 등에서 바로 만든다.
 * - 새 메뉴는 기본적으로 거래처에만 저장(거래처 전용, menu_id AM-…). 재고 관리를 위해 식재료 1개 이상·칼로리 필수.
 * - 본사 메뉴를 거래처로 가져오기(레시피·칼로리 복사).
 * - 사용자가 원할 때만 거래처 전용 메뉴를 같은 menu_id로 본사 메뉴에 반영.
 */
@Service
public class AccountMenuRegistrationService {
	private static final int MAX_NAME_LENGTH = 100;
	private static final BigDecimal MAX_CALORIES = new BigDecimal("5000");

	private final AccountMenuRegistrationMapper mapper;
	private final AccountMenuRecipeMapper recipeMapper;
	private final AccountMenuRecipeService recipeService;

	public AccountMenuRegistrationService(AccountMenuRegistrationMapper mapper, AccountMenuRecipeMapper recipeMapper,
			AccountMenuRecipeService recipeService) {
		this.mapper = mapper;
		this.recipeMapper = recipeMapper;
		this.recipeService = recipeService;
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> register(Map<String, Object> b) {
		String accountId = required(b, "account_id", "거래처");
		String name = required(b, "menu_name", "메뉴명");
		if (name.length() > MAX_NAME_LENGTH)
			throw new IllegalArgumentException("메뉴명은 " + MAX_NAME_LENGTH + "자 이하로 입력해주세요.");
		BigDecimal calories = decimal(b.get("calories_per_serving"));
		if (calories == null || calories.signum() <= 0 || calories.compareTo(MAX_CALORIES) > 0)
			throw new IllegalArgumentException("1인분 칼로리(kcal)를 0보다 크게 입력해주세요.");
		int menuType = code(b, "menu_type", "메뉴유형", 0, 4);
		int menuGubun = code(b, "menu_gubun", "메뉴구분", 0, 18);
		int foodType = code(b, "food_type", "음식 유형", 1, 7);
		int mealPlanType = b.get("meal_plan_type") == null || b.get("meal_plan_type").toString().isBlank() ? 0
				: code(b, "meal_plan_type", "식단 유형", 0, 5);

		if (!(b.get("ingredients") instanceof List<?> rows) || rows.isEmpty())
			throw new IllegalArgumentException("재고 관리를 위해 식재료를 1개 이상 선택해주세요.");
		String key = MenuNames.key(name);
		for (Map<String, Object> menu : mapper.activeAccountMenus(Map.of("account_id", accountId)))
			if (MenuNames.key(String.valueOf(menu.get("menu_name"))).equals(key))
				throw new IllegalArgumentException("이 거래처에 같은 이름의 메뉴(" + menu.get("menu_name") + ")가 이미 있습니다. 비슷한 메뉴에서 선택해주세요.");

		String menuId = newMenuId();
		Map<String, Object> menu = new HashMap<>();
		menu.put("account_id", accountId);
		menu.put("menu_id", menuId);
		menu.put("menu_name_raw", text(b.get("menu_name_raw")) == null ? name : text(b.get("menu_name_raw")));
		menu.put("menu_name", name);
		menu.put("food_type", foodType);
		menu.put("menu_type", menuType);
		menu.put("menu_gubun", menuGubun);
		menu.put("meal_plan_type", mealPlanType);
		menu.put("calories_per_serving", calories);
		menu.put("user_id", b.get("user_id"));
		recipeMapper.upsertMenu(menu);

		Object recipeId = recipeService.ensureRecipeId(new HashMap<>(Map.of("account_id", accountId, "menu_id", menuId)));
		Set<String> seen = new HashSet<>();
		int seq = 0;
		for (Object value : rows) {
			if (!(value instanceof Map<?, ?> raw))
				throw new IllegalArgumentException("식재료 형식을 확인해주세요.");
			Map<String, Object> row = new HashMap<>((Map<String, Object>) raw);
			String ingredientId = required(row, "ingredient_id", "식재료");
			if (!seen.add(ingredientId))
				throw new IllegalArgumentException("같은 식재료를 두 번 넣었습니다. 한 줄로 합쳐주세요.");
			BigDecimal qty = decimal(row.get("qty_num"));
			if (qty == null || qty.signum() <= 0)
				throw new IllegalArgumentException("식재료 1인분 사용량을 0보다 크게 입력해주세요.");
			row.put("account_id", accountId);
			row.put("menu_id", menuId);
			row.put("user_id", b.get("user_id"));
			row.put("ingredient_seq", ++seq);
			row.put("recipe_yield_servings", BigDecimal.ONE);
			row.put("qty_num", qty);
			row.put("qty_raw", qty.stripTrailingZeros().toPlainString() + text(row.get("qty_unit")));
			row.put("recipe_id", recipeId);
			recipeService.ensureMenuIngredient(row);
			String base = recipeMapper.accountIngredientBaseUnit(row);
			QuantityUnits.recipe(row, base);
			if ("1".equals(String.valueOf(row.get("review_flag"))))
				throw new IllegalArgumentException(name(row) + "의 단위(" + text(row.get("qty_unit")) + ")를 기준단위(" + base + ")로 바꿀 수 없습니다. 단위를 확인해주세요.");
			recipeMapper.insertRecipeDetail(row);
		}
		return Map.of("code", 200, "message", "거래처 메뉴로 등록했습니다.", "menu_id", menuId, "menu_name", name,
				"ingredient_count", seq, "standard_yn", "N");
	}

	/** 본사 메뉴를 거래처 메뉴로 가져온다. 거래처에 레시피가 없을 때만 본사 레시피를 복사한다 */
	@Transactional
	public Map<String, Object> importStandard(Map<String, Object> b) {
		String accountId = required(b, "account_id", "거래처");
		String menuId = required(b, "menu_id", "본사 메뉴");
		Map<String, Object> standard = mapper.standardMenu(Map.of("menu_id", menuId));
		if (standard == null || "Y".equals(standard.get("del_yn")))
			throw new IllegalArgumentException("본사 메뉴를 찾을 수 없습니다.");
		Map<String, Object> key = Map.of("account_id", accountId, "menu_id", menuId);
		boolean hasRecipe = !recipeMapper.recipeDetails(key).isEmpty();
		List<Map<String, Object>> recipe = hasRecipe ? List.of() : mapper.standardRecipe(Map.of("menu_id", menuId));
		if (!hasRecipe && recipe.isEmpty())
			throw new IllegalArgumentException("본사 메뉴(" + standard.get("menu_name") + ")에 식재료가 없어 가져올 수 없습니다. 본사 메뉴 관리에서 식재료를 먼저 등록해주세요.");

		Map<String, Object> menu = new HashMap<>(key);
		menu.put("menu_name_raw", standard.get("menu_name"));
		menu.put("menu_name", standard.get("menu_name"));
		for (String field : List.of("food_type", "food_type_reason", "menu_type", "menu_gubun", "meal_plan_type", "calories_per_serving"))
			menu.put(field, standard.get(field));
		menu.put("user_id", b.get("user_id"));
		recipeMapper.upsertMenu(menu);

		for (Map<String, Object> source : recipe) {
			Map<String, Object> row = new HashMap<>(source);
			row.put("account_id", accountId);
			row.put("user_id", b.get("user_id"));
			recipeService.ensureMenuIngredient(row);
			recipeMapper.insertRecipeDetail(row);
		}
		return Map.of("code", 200, "message", "본사 메뉴를 거래처 메뉴로 가져왔습니다.", "menu_id", menuId,
				"menu_name", standard.get("menu_name"), "copied_ingredient_count", recipe.size());
	}

	/**
	 * 본사 반영 전 미리보기: 레시피 식재료를 본사 식자재로 어떻게 옮길지와 반영 불가 사유.
	 * 같은 ID가 본사에 있으면 그것(SAME), 없으면 이름·기준단위가 같은 본사 식자재(MATCHED), 그래도 없으면 거래처 식재료를 같은 ID로 본사에 추가(NEW).
	 */
	public Map<String, Object> promotePreview(Map<String, Object> p) {
		String accountId = required(p, "account_id", "거래처");
		String menuId = required(p, "menu_id", "메뉴");
		Map<String, Object> menu = activeAccountMenu(accountId, menuId);
		Map<String, Object> result = new HashMap<>();
		result.put("menu_id", menuId);
		result.put("menu_name", menu.get("menu_name"));
		result.put("blocked_reason", promoteBlockedReason(menu));
		result.put("ingredients", promotePlan(accountId, menuId));
		return result;
	}

	/** 거래처 전용 메뉴를 같은 menu_id로 본사 메뉴·표준 레시피에 반영 */
	@Transactional
	public Map<String, Object> promote(Map<String, Object> b) {
		String accountId = required(b, "account_id", "거래처");
		String menuId = required(b, "menu_id", "메뉴");
		Map<String, Object> menu = activeAccountMenu(accountId, menuId);
		String blocked = promoteBlockedReason(menu);
		if (blocked != null)
			throw new IllegalArgumentException(blocked);
		List<Map<String, Object>> plan = promotePlan(accountId, menuId);
		if (plan.isEmpty())
			throw new IllegalArgumentException("식재료가 없는 메뉴는 본사 메뉴로 반영할 수 없습니다.");

		int added = 0;
		for (Map<String, Object> row : plan)
			if ("NEW".equals(row.get("action"))) {
				Map<String, Object> ingredient = new HashMap<>();
				ingredient.put("account_id", accountId);
				ingredient.put("ingredient_id", row.get("ingredient_id"));
				ingredient.put("user_id", b.get("user_id"));
				mapper.insertStandardIngredient(ingredient);
				added++;
			}
		Map<String, Object> standard = new HashMap<>(menu);
		standard.put("food_type", menu.get("food_type") == null ? 7 : menu.get("food_type"));
		standard.put("user_id", b.get("user_id"));
		mapper.insertStandardMenu(standard);
		for (Map<String, Object> row : plan) {
			Map<String, Object> recipe = new HashMap<>(row);
			recipe.put("user_id", b.get("user_id"));
			mapper.insertStandardRecipeRow(recipe);
		}
		return Map.of("code", 200, "message", "본사 메뉴에도 등록했습니다.", "menu_id", menuId,
				"copied_ingredient_count", plan.size(), "added_standard_ingredient_count", added);
	}

	private Map<String, Object> activeAccountMenu(String accountId, String menuId) {
		Map<String, Object> menu = mapper.accountMenu(Map.of("account_id", accountId, "menu_id", menuId));
		if (menu == null || "Y".equals(menu.get("del_yn")))
			throw new IllegalArgumentException("거래처 메뉴를 찾을 수 없습니다.");
		return menu;
	}

	private String promoteBlockedReason(Map<String, Object> menu) {
		if (mapper.standardMenu(Map.of("menu_id", menu.get("menu_id"))) != null)
			return "이미 본사 메뉴에 있는 메뉴입니다.";
		String nameKey = MenuNames.key(String.valueOf(menu.get("menu_name")));
		for (Map<String, Object> standard : mapper.activeStandardMenus(Map.of()))
			if (MenuNames.key(String.valueOf(standard.get("menu_name"))).equals(nameKey))
				return "본사에 같은 이름의 메뉴(" + standard.get("menu_name") + ", " + standard.get("menu_id") + ")가 이미 있습니다. 본사 메뉴 관리에서 확인해주세요.";
		return null;
	}

	/** 레시피 행마다 action(SAME/MATCHED/NEW)과 옮길 본사 식자재 ID(standard_ingredient_id)·이름(standard_name)을 붙인다 */
	private List<Map<String, Object>> promotePlan(String accountId, String menuId) {
		List<Map<String, Object>> recipe = mapper.accountRecipe(Map.of("account_id", accountId, "menu_id", menuId));
		List<Map<String, Object>> standards = recipe.stream().anyMatch(row -> row.get("standard_ingredient_id") == null)
				? mapper.standardIngredients(Map.of()) : List.of();
		List<Map<String, Object>> plan = new ArrayList<>();
		for (Map<String, Object> source : recipe) {
			Map<String, Object> row = new HashMap<>(source);
			if (row.get("standard_ingredient_id") != null) {
				row.put("action", "SAME");
				row.put("standard_name", row.get("ingredient_name"));
			} else {
				String key = MenuNames.key(String.valueOf(row.get("ingredient_name")));
				String unit = String.valueOf(row.get("base_unit"));
				Map<String, Object> match = standards.stream()
						.filter(item -> unit.equalsIgnoreCase(String.valueOf(item.get("base_unit"))))
						.filter(item -> key.equals(MenuNames.key(String.valueOf(item.get("ingredient_name_std"))))
								|| key.equals(MenuNames.key(String.valueOf(item.get("ingredient_name_raw")))))
						.findFirst().orElse(null);
				row.put("action", match == null ? "NEW" : "MATCHED");
				row.put("standard_ingredient_id", match == null ? row.get("ingredient_id") : match.get("ingredient_id"));
				row.put("standard_name", match == null ? row.get("ingredient_name") : match.get("ingredient_name_std"));
			}
			plan.add(row);
		}
		return plan;
	}

	/** 등록 창 식재료 목록: 거래처 식재료 */
	public List<Map<String, Object>> ingredients(Map<String, Object> p) {
		return mapper.accountIngredients(Map.of("account_id", required(p, "account_id", "거래처")));
	}

	/** 거래처 식재료 직접 추가(본사 식자재 없이). ID는 AI-… */
	@Transactional
	public Map<String, Object> registerIngredient(Map<String, Object> b) {
		String accountId = required(b, "account_id", "거래처");
		String name = required(b, "ingredient_name", "식재료명");
		if (name.length() > MAX_NAME_LENGTH)
			throw new IllegalArgumentException("식재료명은 " + MAX_NAME_LENGTH + "자 이하로 입력해주세요.");
		String unit = required(b, "base_unit", "기준단위");
		if (!List.of("g", "ml", "EA").contains(unit))
			throw new IllegalArgumentException("기준단위는 g·ml·EA 중에서 골라주세요.");
		String key = MenuNames.key(name);
		for (Map<String, Object> existing : mapper.accountIngredients(Map.of("account_id", accountId)))
			if (MenuNames.key(String.valueOf(existing.get("ingredient_name"))).equals(key))
				throw new IllegalArgumentException("이미 있는 식재료(" + existing.get("ingredient_name") + ")입니다. 목록에서 골라주세요.");
		String id = newId("AI-", candidate -> mapper.ingredientIdTaken(Map.of("ingredient_id", candidate)) == 0);
		String category = text(b.get("category_name"));
		Map<String, Object> row = new HashMap<>();
		row.put("account_id", accountId);
		row.put("ingredient_id", id);
		row.put("ingredient_name_raw", name);
		row.put("ingredient_name_std", name);
		row.put("category_name", category);
		row.put("base_unit", unit);
		row.put("user_id", b.get("user_id"));
		recipeMapper.upsertIngredient(row);
		return Map.of("code", 200, "ingredient_id", id, "ingredient_name", name, "base_unit", unit,
				"category_name", category == null ? "" : category);
	}

	/** AM-로 시작하는 거래처 전용 메뉴 ID. 본사 메뉴 화면의 다음 ID 계산(영문+숫자)에 섞이지 않도록 하이픈을 넣는다 */
	String newMenuId() {
		return newId("AM-", candidate -> mapper.menuIdTaken(Map.of("menu_id", candidate)) == 0);
	}

	private static String newId(String prefix, java.util.function.Predicate<String> free) {
		for (int attempt = 0; attempt < 5; attempt++) {
			String id = prefix + Long.toString(System.currentTimeMillis(), 36).toUpperCase()
					+ Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36), 36).toUpperCase();
			if (free.test(id))
				return id;
		}
		throw new IllegalStateException("ID를 만들지 못했습니다. 다시 시도해주세요.");
	}

	private static String name(Map<String, Object> row) {
		String value = text(row.get("ingredient_name_raw"));
		return value == null ? String.valueOf(row.get("ingredient_id")) : value;
	}

	private static String required(Map<String, Object> b, String field, String label) {
		String value = text(b.get(field));
		if (value == null)
			throw new IllegalArgumentException(label + "을(를) 입력해주세요.");
		return value;
	}

	private static int code(Map<String, Object> b, String field, String label, int min, int max) {
		String value = text(b.get(field));
		try {
			int code = Integer.parseInt(value == null ? "" : value);
			if (code >= min && code <= max)
				return code;
		} catch (NumberFormatException ignored) {
			// 아래에서 같은 안내
		}
		throw new IllegalArgumentException(label + "을(를) 선택해주세요.");
	}

	private static BigDecimal decimal(Object value) {
		String text = text(value);
		try {
			return text == null ? null : new BigDecimal(text);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String text(Object value) {
		if (value == null)
			return null;
		String text = value.toString().strip();
		return text.isEmpty() ? null : text;
	}
}
