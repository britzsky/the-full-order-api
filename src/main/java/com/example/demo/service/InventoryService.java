package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.InventoryMapper;

@Service
public class InventoryService {

	InventoryMapper inventoryMapper;
	private final StockAvailabilityService availability;

	public InventoryService(InventoryMapper inventoryMapper, StockAvailabilityService availability) {
		this.inventoryMapper = inventoryMapper;
		this.availability = availability;
	}

	// 재고관리 -> 거래처 재고 조회
	public List<Map<String, Object>> AccountInventoryList(Map<String, Object> paramMap) {
		require(paramMap, "account_id");
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = inventoryMapper.AccountInventoryList(paramMap);
		availability.enrich(resultList, paramMap, false);
		addOpeningEstimates(resultList, paramMap);
		return resultList;
	}

	/** 기초재고 등록 가능한 행에 공급가 기반 추정 단가(재고 기준단위당)와 출처·기준일을 붙인다. */
	private void addOpeningEstimates(List<Map<String, Object>> rows, Map<String, Object> params) {
		if (rows == null || rows.stream().noneMatch(row -> "Y".equals(String.valueOf(row.get("opening_available")))))
			return;
		Map<String, Object> key = new java.util.HashMap<>();
		key.put("account_id", params.get("account_id"));
		key.put("inventory_balance_id", params.get("inventory_balance_id"));
		Map<String, Map<String, Object>> estimates = new java.util.HashMap<>();
		for (Map<String, Object> estimate : inventoryMapper.openingCostEstimates(key))
			estimates.put(String.valueOf(estimate.get("inventory_balance_id")), estimate);
		for (Map<String, Object> row : rows) {
			if (!"Y".equals(String.valueOf(row.get("opening_available"))))
				continue;
			Map<String, Object> estimate = estimates.get(String.valueOf(row.get("inventory_balance_id")));
			BigDecimal cost = estimatedCost(estimate, row.get("base_unit"));
			if (cost == null)
				continue;
			row.put("estimated_unit_cost", cost);
			row.put("estimated_cost_source", estimate.get("cost_source"));
			row.put("estimated_cost_date", estimate.get("cost_date") == null ? null : estimate.get("cost_date").toString());
		}
	}

	/** 추정 단가를 재고 기준단위당 금액으로 환산한다 (원/kg → 원/g 등). 환산할 수 없거나 단가가 없으면 null. */
	static BigDecimal estimatedCost(Map<String, Object> estimate, Object stockUnit) {
		if (estimate == null || estimate.get("unit_cost") == null)
			return null;
		BigDecimal cost = decimal(estimate.get("unit_cost"));
		if (cost.signum() <= 0)
			return null;
		try {
			// 1 재고단위가 가격단위로 몇인지 곱한다: 원/kg × 0.001 = 원/g
			return cost.multiply(QuantityUnits.convert(BigDecimal.ONE, stockUnit, estimate.get("price_unit")))
					.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public List<Map<String, Object>> movements(Map<String, Object> params) {
		Map<String, Object> query=movementFilter(params);
		int limit=Integer.parseInt(String.valueOf(params.getOrDefault("limit",50)));
		int offset=Integer.parseInt(String.valueOf(params.getOrDefault("offset",0)));
		if(limit<1 || limit>200 || offset<0) throw new IllegalArgumentException("이력 조회 범위를 확인해주세요.");
		query.put("limit",limit);query.put("offset",offset);
		return inventoryMapper.movements(query);
	}

	// 재고 변동 이력과 같은 조건의 구분별 건수·수량·금액 합계
	public List<Map<String, Object>> movementSummary(Map<String, Object> params) {
		return inventoryMapper.movementSummary(movementFilter(params));
	}

	private static final java.util.Set<String> MOVEMENT_TYPES=java.util.Set.of("IN","OUT","ADJUST","RETURN","DISCARD","OPENING");

	// 이력 조회 공통 조건: 날짜 형식 검증, movement_type은 쉼표 목록에서 허용값만 남긴다
	private Map<String, Object> movementFilter(Map<String, Object> params) {
		require(params,"account_id");
		Map<String, Object> query=new java.util.HashMap<>(params);
		for(String key:List.of("date_from","date_to")) if(params.get(key)!=null && !params.get(key).toString().isBlank())
			java.time.LocalDate.parse(params.get(key).toString());
		Object keyword=params.get("keyword");
		query.put("keyword",keyword==null ? null : keyword.toString().trim());
		Object types=params.get("movement_type");
		if(types!=null && !types.toString().isBlank()) {
			List<String> allowed=java.util.Arrays.stream(types.toString().split(",")).map(String::trim).map(String::toUpperCase)
					.filter(MOVEMENT_TYPES::contains).distinct().toList();
			if(allowed.isEmpty()) throw new IllegalArgumentException("변동 구분을 확인해주세요.");
			query.put("movement_types",allowed);
		}
		return query;
	}

	// 거래처별 재고와 분리된 본사 식자재 마스터를 조회한다.
	public List<Map<String, Object>> ingredientMasters(Map<String, Object> params) {
		return inventoryMapper.ingredientMasterList(params);
	}

	// 본사 식자재의 기준 단위와 환산값은 메뉴 원가 계산의 기준이므로 저장 전에 필수값을 검증한다.
	public Map<String, Object> createIngredientMaster(Map<String, Object> body) {
		require(body, "ingredient_id", "ingredient_name", "base_unit");
		normalizeIngredientMaster(body);
		inventoryMapper.insertIngredientMaster(body);
		return ok(body.get("ingredient_id"));
	}

	public Map<String, Object> updateIngredientMaster(Map<String, Object> body) {
		require(body, "ingredient_id", "ingredient_name", "base_unit");
		normalizeIngredientMaster(body);
		if (inventoryMapper.updateIngredientMaster(body) == 0)
			throw new IllegalArgumentException("Ingredient master was not found.");
		return ok(body.get("ingredient_id"));
	}

	private void normalizeIngredientMaster(Map<String, Object> body) {
		Object convert = body.get("convert_value");
		BigDecimal value = convert == null || convert.toString().isBlank() ? BigDecimal.ONE
				: new BigDecimal(convert.toString());
		if (value.signum() <= 0)
			throw new IllegalArgumentException("convert_value must be greater than zero.");
		body.put("convert_value", value);
		// 본사 표준 단가(기준단위당 원). 거래처에 입고 이력이 없을 때 식단 원가 계산에 쓴다. 비우면 미등록.
		Object standard = body.get("standard_unit_cost");
		if (standard == null || standard.toString().isBlank()) {
			body.put("standard_unit_cost", null);
		} else {
			BigDecimal cost;
			try { cost = new BigDecimal(standard.toString().trim()); }
			catch (NumberFormatException e) { throw new IllegalArgumentException("표준 단가는 숫자로 입력해주세요."); }
			if (cost.signum() < 0 || cost.stripTrailingZeros().scale() > 6)
				throw new IllegalArgumentException("표준 단가는 0 이상, 소수점 6자리 이하로 입력해주세요.");
			body.put("standard_unit_cost", cost);
		}
	}

	/** 식재료 재고 행을 만든다(이미 있으면 그 행). 수량은 기초재고 등록·조정으로 넣는다(금액 없는 재고를 만들지 않음). */
	@Transactional
	public Map<String, Object> create(Map<String, Object> body) {
		require(body, "account_id", "ingredient_id");
		Object id = ensureBalance(body);
		body.put("inventory_balance_id", id);
		return ok(id);
	}

	/** 거래처 식재료의 재고 행 ID. 없으면 수량 0으로 만든다(재고 기준단위 = 거래처 식재료 기준단위). */
	Object ensureBalance(Map<String, Object> body) {
		Map<String, Object> ingredient = inventoryMapper.accountIngredient(body);
		if (ingredient == null)
			throw new IllegalArgumentException("거래처 식재료를 찾을 수 없습니다.");
		Map<String, Object> key = new java.util.HashMap<>(body);
		key.put("base_unit", ingredient.get("base_unit"));
		if (key.get("location_id") == null || key.get("location_id").toString().isBlank())
			key.put("location_id", ingredient.get("default_location_id") == null ? "L999" : ingredient.get("default_location_id"));
		inventoryMapper.ensureBalance(key);
		Long id = inventoryMapper.balanceId(key);
		if (id == null)
			throw new IllegalStateException("재고 행을 만들지 못했습니다.");
		return id;
	}

	/** 화면은 재고가 아직 없는 식재료도 보여주므로, 재고 행 ID가 없으면 거래처·식재료로 찾거나 만든다. */
	private void resolveBalance(Map<String, Object> body) {
		if (body.get("inventory_balance_id") != null && !body.get("inventory_balance_id").toString().isBlank())
			return;
		require(body, "account_id", "ingredient_id");
		body.put("inventory_balance_id", ensureBalance(body));
	}

	@Transactional
	public Map<String, Object> update(Map<String, Object> body) {
		resolveBalance(body);
		Map<String, Object> stored = inventoryMapper.inventoryForUpdate(body);
		if (stored == null)
			throw new IllegalArgumentException("Inventory was not found.");
		body.put("base_unit", stored.get("base_unit"));
		normalizeCurrentQuantity(body);
		BigDecimal before = decimal(stored.get("current_base_qty"));
		BigDecimal after = body.get("current_base_qty") == null ? before : (BigDecimal) body.get("current_base_qty");
		BigDecimal delta = after.subtract(before);
		if (delta.signum() == 0) {
			// 수량 변화 없음(단위·위치·메모만 변경): 이력·금액 변화 없이 저장
			if (inventoryMapper.updateInventory(body) == 0)
				throw new IllegalArgumentException("Inventory was not found.");
		} else {
			// 실사 조정: 수량 차이를 평균단가로 금액에 반영하고 ADJUST 이력을 남긴다
			BigDecimal average = decimal(stored.get("average_unit_cost"));
			if (delta.signum() > 0 && average.signum() == 0)
				throw new IllegalArgumentException("평균단가가 없어 재고 금액을 계산할 수 없습니다. 먼저 '기초재고 등록'에서 수량과 단가를 입력해주세요.");
			BigDecimal amountBefore = decimal(stored.get("inventory_amount"));
			BigDecimal amountAfter = after.signum() == 0 ? BigDecimal.ZERO
					: amountBefore.add(delta.multiply(average)).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
			saveValued(body, stored, "ADJUST", before, after, average, amountBefore, amountAfter, "MANUAL", null);
		}
		if (body.get("safe_stock_base_qty") != null || body.get("max_stock_base_qty") != null || body.get("lead_time_days") != null) {
			Map<String, Object> settings = new java.util.HashMap<>(body);
			settings.put("account_id", stored.get("account_id"));
			settings.put("ingredient_id", stored.get("ingredient_id"));
			inventoryMapper.updateIngredientStockSettings(settings);
		}
		return ok(body.get("inventory_balance_id"));
	}

	/**
	 * 기초재고 등록: 프로그램 도입 전부터 보유하던 재고를 수량과 단가(기준단위당)로 등록한다.
	 * 입고·사용 등 ADJUST 이외 이력이 생긴 뒤에는 불가하고, 재고행당 한 번만 가능하다(이력 유니크 키).
	 * 원산지는 당시 기록이 없으므로 LOT를 만들지 않는다(미확인).
	 * 단가(unit_cost)를 생략하면 공급가·최근 입고 기반 추정 단가를 쓰고 이력 근거를 OPENING_ESTIMATED로 남긴다.
	 */
	@Transactional
	public Map<String, Object> opening(Map<String, Object> body) {
		require(body, "current_qty");
		resolveBalance(body);
		Map<String, Object> stored = inventoryMapper.inventoryForUpdate(body);
		if (stored == null)
			throw new IllegalArgumentException("Inventory was not found.");
		if (inventoryMapper.hasNonAdjustMovement(stored) > 0)
			throw new IllegalArgumentException("이미 입고·사용·기초재고 이력이 있어 기초재고를 등록할 수 없습니다. 재고 조정을 사용해주세요.");
		body.put("base_unit", stored.get("base_unit"));
		normalizeCurrentQuantity(body);
		boolean estimated = body.get("unit_cost") == null || body.get("unit_cost").toString().isBlank();
		BigDecimal unitCost;
		if (estimated) {
			Map<String, Object> key = new java.util.HashMap<>();
			key.put("account_id", stored.get("account_id"));
			key.put("inventory_balance_id", stored.get("inventory_balance_id"));
			List<Map<String, Object>> estimates = inventoryMapper.openingCostEstimates(key);
			unitCost = estimatedCost(estimates.isEmpty() ? null : estimates.get(0), stored.get("base_unit"));
			if (unitCost == null)
				throw new IllegalArgumentException("입고 단가·본사 표준 단가가 없어 단가를 추정할 수 없습니다. 기준단위당 단가를 직접 입력해주세요.");
		} else {
			unitCost = decimal(body.get("unit_cost"));
		}
		BigDecimal after = (BigDecimal) body.get("current_base_qty");
		if (unitCost.signum() < 0 || (after.signum() > 0 && unitCost.signum() == 0))
			throw new IllegalArgumentException("기초재고 단가(기준단위당)를 0보다 크게 입력해주세요.");
		BigDecimal amountAfter = after.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
		try {
			saveValued(body, stored, "OPENING", decimal(stored.get("current_base_qty")), after, unitCost,
					decimal(stored.get("inventory_amount")), amountAfter, estimated ? "OPENING_ESTIMATED" : "OPENING",
					String.valueOf(stored.get("inventory_balance_id")));
		} catch (org.springframework.dao.DuplicateKeyException e) {
			throw new IllegalArgumentException("이 재고의 기초재고는 이미 등록되었습니다.");
		}
		if (estimated)
			return Map.of("code", 200, "message", "success", "id", body.get("inventory_balance_id"), "unit_cost", unitCost,
					"cost_estimated", true);
		return ok(body.get("inventory_balance_id"));
	}

	/** 수량·평균단가·재고금액을 갱신하고 금액이 있는 이력을 남긴다. */
	private void saveValued(Map<String, Object> body, Map<String, Object> stored, String type, BigDecimal before,
			BigDecimal after, BigDecimal averageAfter, BigDecimal amountBefore, BigDecimal amountAfter,
			String referenceType, String referenceId) {
		BigDecimal averageBefore = decimal(stored.get("average_unit_cost"));
		body.put("average_unit_cost", averageAfter);
		body.put("inventory_amount", amountAfter);
		if (inventoryMapper.updateInventoryValue(body) == 0)
			throw new IllegalArgumentException("Inventory was not found.");
		String movementId = UUID.randomUUID().toString();
		Map<String, Object> movement = new java.util.HashMap<>();
		movement.put("movement_id", movementId);
		movement.put("account_id", stored.get("account_id"));
		movement.put("ingredient_id", stored.get("ingredient_id"));
		movement.put("location_id", stored.get("location_id"));
		movement.put("movement_type", type);
		movement.put("quantity_delta", after.subtract(before));
		movement.put("quantity_before", before);
		movement.put("quantity_after", after);
		movement.put("unit_cost_snapshot", averageAfter);
		movement.put("amount_delta", amountAfter.subtract(amountBefore));
		movement.put("average_unit_cost_before", averageBefore);
		movement.put("average_unit_cost_after", averageAfter);
		movement.put("base_unit", stored.get("base_unit"));
		movement.put("reference_type", referenceType);
		movement.put("reference_id", referenceId == null ? movementId : referenceId);
		movement.put("user_id", body.get("user_id"));
		inventoryMapper.insertValuedMovement(movement);
	}

	private static BigDecimal decimal(Object value) {
		return value == null || value.toString().isBlank() ? BigDecimal.ZERO : new BigDecimal(value.toString());
	}

	/** 입력 수량을 재고 기준단위로 환산한다(kg↔g, L↔ml). 재고는 식재료 단위라 상품 포장 단위로는 받지 않는다. */
	void normalizeCurrentQuantity(Map<String, Object> body) {
		if (body.get("current_qty") == null)
			return;
		String base = body.get("base_unit") == null ? "" : body.get("base_unit").toString();
		String current = body.get("current_unit") == null || body.get("current_unit").toString().isBlank() ? base
				: body.get("current_unit").toString();
		BigDecimal qty;
		try {
			qty = QuantityUnits.convert(new BigDecimal(body.get("current_qty").toString()), current, base);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("'" + current + "' 단위는 이 재고의 기준단위(" + base
					+ ")로 환산할 수 없습니다. 입력 가능한 단위: " + String.join(", ", allowedUnits(base)));
		}
		if (qty.signum() < 0)
			throw new IllegalArgumentException("재고는 음수일 수 없습니다.");
		body.put("current_base_qty", qty);
		body.put("current_unit", current);
	}

	/** 재고 입력 화면에서 고를 수 있는 단위: 기준단위와 같은 차원의 큰 단위 */
	static java.util.List<String> allowedUnits(String base) {
		java.util.List<String> units = new java.util.ArrayList<>();
		String b = QuantityUnits.unit(base);
		units.add(b);
		if (b.equals("g"))
			units.add("kg");
		if (b.equals("ml"))
			units.add("L");
		return units;
	}

	public Map<String, Object> delete(Map<String, Object> body) {
		require(body, "inventory_balance_id");
		if (inventoryMapper.deleteInventory(body) == 0)
			throw new IllegalArgumentException("재고가 남아 있거나 사용 이력이 있는 재고 행은 지울 수 없습니다.");
		return ok(body.get("inventory_balance_id"));
	}

	@Transactional
	public Map<String, Object> move(Map<String, Object> body) {
		require(body, "inventory_balance_id", "movement_type", "quantity_delta");
		Map<String, Object> current = inventoryMapper.inventoryForUpdate(body);
		if (current == null)
			throw new IllegalArgumentException("Inventory was not found.");
		BigDecimal before = new BigDecimal(current.get("current_base_qty").toString());
		BigDecimal delta = new BigDecimal(body.get("quantity_delta").toString());
		BigDecimal after = before.add(delta);
		if (after.signum() < 0)
			throw new IllegalArgumentException("Inventory cannot be negative.");
		body.put("account_id", current.get("account_id"));
		body.put("ingredient_id", current.get("ingredient_id"));
		body.put("location_id", current.get("location_id"));
		body.put("base_unit", current.get("base_unit"));
		body.put("quantity_before", before);
		body.put("quantity_after", after);
		body.put("movement_id", UUID.randomUUID().toString());
		body.put("current_base_qty", after);
		inventoryMapper.updateInventory(body);
		inventoryMapper.insertMovement(body);
		return ok(body.get("movement_id"));
	}

	private void require(Map<String, Object> b, String... fs) {
		for (String f : fs)
			if (b.get(f) == null || b.get(f).toString().isBlank())
				throw new IllegalArgumentException(f + " is required.");
	}

	private Map<String, Object> ok(Object id) {
		return Map.of("code", 200, "message", "success", "id", id);
	}
}
