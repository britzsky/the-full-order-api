package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import com.example.demo.mapper.AccountMenuRegistrationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 식단표에 있지만 거래처·본사 메뉴에 없는 메뉴의 레시피 초안을 AI로 만든다(저장하지 않음).
 * AI는 1인분 칼로리와 식재료·1인분 사용량(g/ml/EA)만 제안하고, 식재료 이름은 거래처 식재료와 이름(MenuNames.key)이 같을 때만 연결한다.
 * 사람이 화면에서 확인·수정한 뒤 기존 거래처 메뉴 등록(/menus/register-new)으로 저장한다 — AI 결과를 그대로 저장하지 않는다.
 */
@Service
public class MenuRecipeDraftService {
	public static final String FEATURE = "MENU_RECIPE_DRAFT";
	static final int MAX_MENUS = 10;
	/** 프롬프트에 넣는 거래처 식재료 이름 수 상한(입력 토큰·비용 제한) */
	static final int MAX_INGREDIENT_NAMES = 1200;
	private static final int MAX_TEXT_LENGTH = 100;

	private static final String SYSTEM = String.join("\n",
			"너는 한국 단체급식(요양원·어린이집·사업장) 영양사다. 식단표에 적힌 메뉴마다 1인분 레시피 초안을 만든다.",
			"- calories_per_serving: 1인분 추정 열량(kcal, 정수).",
			"- ingredients: 발주·재고 계산에 쓰는 주요 식재료만 최대 8개. 물·소금처럼 발주하지 않는 것은 빼고, 양념은 간장·고추장처럼 양이 의미 있는 것만.",
			"- qty: 1인분 사용량. unit은 g·ml·EA 중 하나(고기·채소·곡물 g, 액체 ml, 달걀·빵 같은 낱개 EA).",
			"- name: 아래 '거래처 식재료' 목록에 맞는 재료가 있으면 그 이름을 글자 그대로 쓴다. 없으면 흔히 쓰는 짧은 식재료명(예: 돼지고기 앞다리, 애호박).",
			"- category: 식재료 분류(채소·육류·수산·곡류·유제품·가공식품·양념 등).",
			"- menu_name: 표기에서 알레르기 번호·괄호 안 안내·특수기호를 뺀 메뉴명. text는 입력 표기를 그대로 돌려준다.",
			"- 메뉴가 아닌 글자(행사·안내 문구)라도 menus에 넣되 ingredients를 빈 배열로 둔다.");

	private final AccountMenuRegistrationMapper mapper;
	private final OpenAiClient openAi;
	private final ObjectMapper objectMapper;

	public MenuRecipeDraftService(AccountMenuRegistrationMapper mapper, OpenAiClient openAi, ObjectMapper objectMapper) {
		this.mapper = mapper;
		this.openAi = openAi;
		this.objectMapper = objectMapper;
	}

	public Map<String, Object> draft(Map<String, Object> b, String userId) {
		Object accountValue = b.get("account_id");
		String accountId = accountValue == null ? "" : accountValue.toString().strip();
		if (accountId.isEmpty())
			throw new IllegalArgumentException("거래처를 선택해주세요.");
		List<String> texts = texts(b.get("menus"));
		if (texts.isEmpty())
			throw new IllegalArgumentException("레시피 초안을 만들 메뉴를 선택해주세요.");
		if (texts.size() > MAX_MENUS)
			throw new IllegalArgumentException("한 번에 " + MAX_MENUS + "개까지 만들 수 있습니다.");

		List<Map<String, Object>> ingredients = mapper.accountIngredients(Map.of("account_id", accountId));
		String user = prompt(texts, promptIngredients(texts, ingredients));
		String output;
		try {
			output = openAi.respond(new OpenAiClient.Caller(FEATURE, accountId, userId, texts.size()), SYSTEM, user, "menu_recipe_drafts", schema());
		} catch (IllegalStateException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
		JsonNode menus;
		try {
			menus = objectMapper.readTree(output).path("menus");
		} catch (Exception e) {
			throw new IllegalArgumentException("AI 응답을 읽지 못했습니다. 다시 시도해주세요.");
		}
		return Map.of("drafts", match(texts, menus, ingredients), "model", openAi.model());
	}

	/** 입력 메뉴 표기(중복 제거, 순서 유지) */
	private static List<String> texts(Object value) {
		Set<String> texts = new LinkedHashSet<>();
		if (value instanceof List<?> list)
			for (Object item : list) {
				Object text = item instanceof Map<?, ?> map ? map.get("text") : item;
				String s = text == null ? "" : text.toString().strip();
				if (s.isEmpty())
					continue;
				if (s.length() > MAX_TEXT_LENGTH)
					throw new IllegalArgumentException("메뉴 표기가 너무 깁니다: " + s.substring(0, 20) + "…");
				texts.add(s);
			}
		return new ArrayList<>(texts);
	}

	/** 메뉴 표기에 이름이 든 식재료를 앞에 두고 상한까지 '이름(단위)'로 */
	static List<String> promptIngredients(List<String> texts, List<Map<String, Object>> ingredients) {
		String menuKeys = String.join("|", texts.stream().map(MenuNames::key).toList());
		List<String> first = new ArrayList<>(), rest = new ArrayList<>();
		for (Map<String, Object> row : ingredients) {
			String name = String.valueOf(row.get("ingredient_name"));
			String key = MenuNames.key(name);
			String label = name + "(" + row.get("base_unit") + ")";
			(key.length() >= 2 && menuKeys.contains(key) ? first : rest).add(label);
		}
		first.addAll(rest);
		return first.size() > MAX_INGREDIENT_NAMES ? first.subList(0, MAX_INGREDIENT_NAMES) : first;
	}

	private static String prompt(List<String> texts, List<String> ingredientNames) {
		StringBuilder s = new StringBuilder("메뉴 표기:\n");
		for (String text : texts)
			s.append("- ").append(text).append('\n');
		s.append("\n거래처 식재료(이름(기준단위)):\n");
		s.append(ingredientNames.isEmpty() ? "(없음)" : String.join(", ", ingredientNames));
		return s.toString();
	}

	/** AI 결과를 입력 표기 순서로 맞추고, 식재료를 거래처 식재료에 이름으로 연결한다 */
	List<Map<String, Object>> match(List<String> texts, JsonNode menus, List<Map<String, Object>> ingredients) {
		Map<String, List<Map<String, Object>>> byKey = new HashMap<>();
		for (Map<String, Object> row : ingredients)
			byKey.computeIfAbsent(MenuNames.key(String.valueOf(row.get("ingredient_name"))), k -> new ArrayList<>()).add(row);
		Map<String, JsonNode> byText = new HashMap<>();
		for (JsonNode menu : menus)
			byText.putIfAbsent(menu.path("text").asText("").strip(), menu);

		List<Map<String, Object>> drafts = new ArrayList<>();
		for (int i = 0; i < texts.size(); i++) {
			String text = texts.get(i);
			JsonNode menu = byText.get(text);
			if (menu == null && i < menus.size())
				menu = menus.get(i);
			Map<String, Object> draft = new LinkedHashMap<>();
			draft.put("text", text);
			if (menu == null) {
				draft.put("menu_name", text);
				draft.put("calories_per_serving", null);
				draft.put("ingredients", List.of());
				draft.put("error", "AI가 이 메뉴의 초안을 돌려주지 않았습니다.");
				drafts.add(draft);
				continue;
			}
			String name = menu.path("menu_name").asText("").strip();
			draft.put("menu_name", name.isEmpty() ? text : name);
			int kcal = menu.path("calories_per_serving").asInt(0);
			draft.put("calories_per_serving", kcal > 0 && kcal <= 5000 ? kcal : null);
			List<Map<String, Object>> rows = new ArrayList<>();
			Set<String> usedIds = new LinkedHashSet<>();
			for (JsonNode item : menu.path("ingredients")) {
				String aiName = item.path("name").asText("").strip();
				BigDecimal qty = item.path("qty").isNumber() ? item.path("qty").decimalValue().setScale(2, RoundingMode.HALF_UP).stripTrailingZeros() : null;
				String unit = item.path("unit").asText("");
				if (aiName.isEmpty() || qty == null || qty.signum() <= 0)
					continue;
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("ai_name", aiName);
				row.put("qty", qty.toPlainString());
				row.put("unit", unit);
				row.put("category", item.path("category").asText(""));
				Map<String, Object> hit = pick(byKey.get(MenuNames.key(aiName)), unit);
				// 같은 거래처 식재료로 두 번 연결되면 뒤의 것은 뺀다(등록은 식재료 중복을 거부)
				if (hit != null && !usedIds.add(String.valueOf(hit.get("ingredient_id"))))
					continue;
				if (hit != null) {
					row.put("ingredient_id", hit.get("ingredient_id"));
					row.put("ingredient_name", hit.get("ingredient_name"));
					row.put("base_unit", hit.get("base_unit"));
					// AI 단위와 거래처 식재료 기준단위가 다르면 사용량을 사람이 고쳐야 한다
					row.put("unit_mismatch", !unit.equalsIgnoreCase(String.valueOf(hit.get("base_unit"))));
				} else {
					row.put("ingredient_id", null);
					row.put("unit_mismatch", false);
				}
				rows.add(row);
			}
			draft.put("ingredients", rows);
			drafts.add(draft);
		}
		return drafts;
	}

	/** 이름이 같은 거래처 식재료가 여럿이면 단위가 같은 것 */
	private static Map<String, Object> pick(List<Map<String, Object>> candidates, String unit) {
		if (candidates == null || candidates.isEmpty())
			return null;
		return candidates.stream().filter(c -> unit.equalsIgnoreCase(String.valueOf(c.get("base_unit")))).findFirst().orElse(candidates.get(0));
	}

	/** 구조화 출력 스키마(strict: 모든 속성 required, additionalProperties false) */
	static Map<String, Object> schema() {
		Map<String, Object> ingredient = object(Map.of(
				"name", Map.of("type", "string"),
				"qty", Map.of("type", "number"),
				"unit", Map.of("type", "string", "enum", List.of("g", "ml", "EA")),
				"category", Map.of("type", "string")));
		Map<String, Object> menu = object(Map.of(
				"text", Map.of("type", "string"),
				"menu_name", Map.of("type", "string"),
				"calories_per_serving", Map.of("type", "integer"),
				"ingredients", Map.of("type", "array", "items", ingredient)));
		return object(Map.of("menus", Map.of("type", "array", "items", menu)));
	}

	private static Map<String, Object> object(Map<String, Object> properties) {
		return Map.of("type", "object", "additionalProperties", false, "properties", properties,
				"required", new ArrayList<>(properties.keySet()));
	}
}
