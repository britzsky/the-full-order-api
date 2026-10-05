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
		return resultList;
	}

	public List<Map<String, Object>> movements(Map<String, Object> params) {
		require(params,"account_id");
		int limit=Integer.parseInt(String.valueOf(params.getOrDefault("limit",50)));
		int offset=Integer.parseInt(String.valueOf(params.getOrDefault("offset",0)));
		if(limit<1 || limit>200 || offset<0) throw new IllegalArgumentException("이력 조회 범위를 확인해주세요.");
		params.put("limit",limit);params.put("offset",offset);
		for(String key:List.of("date_from","date_to")) if(params.get(key)!=null && !params.get(key).toString().isBlank())
			java.time.LocalDate.parse(params.get(key).toString());
		return inventoryMapper.movements(params);
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
	}

	public Map<String, Object> create(Map<String, Object> body) {
		require(body, "account_id", "account_ingredient_product_id", "base_unit");
		normalizeCurrentQuantity(body, inventoryMapper.productPack(body));
		inventoryMapper.insertInventory(body);
		return ok(body.get("inventory_balance_id"));
	}

	@Transactional
	public Map<String, Object> update(Map<String, Object> body) {
		require(body, "inventory_balance_id");
		Map<String, Object> stored = inventoryMapper.inventoryForUpdate(body);
		if (stored == null)
			throw new IllegalArgumentException("Inventory was not found.");
		body.put("base_unit", stored.get("base_unit"));
		normalizeCurrentQuantity(body, inventoryMapper.productPack(productKey(stored)));
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
		if (body.get("account_ingredient_product_id") != null && body.get("safe_stock_base_qty") != null) {
			inventoryMapper.updateAccountProduct(body);
		}
		return ok(body.get("inventory_balance_id"));
	}

	/**
	 * 기초재고 등록: 프로그램 도입 전부터 보유하던 재고를 수량과 단가(기준단위당)로 등록한다.
	 * 입고·사용 등 ADJUST 이외 이력이 생긴 뒤에는 불가하고, 재고행당 한 번만 가능하다(이력 유니크 키).
	 * 원산지는 당시 기록이 없으므로 LOT를 만들지 않는다(미확인).
	 */
	@Transactional
	public Map<String, Object> opening(Map<String, Object> body) {
		require(body, "inventory_balance_id", "current_qty", "unit_cost");
		Map<String, Object> stored = inventoryMapper.inventoryForUpdate(body);
		if (stored == null)
			throw new IllegalArgumentException("Inventory was not found.");
		if (inventoryMapper.hasNonAdjustMovement(stored) > 0)
			throw new IllegalArgumentException("이미 입고·사용·기초재고 이력이 있어 기초재고를 등록할 수 없습니다. 재고 조정을 사용해주세요.");
		body.put("base_unit", stored.get("base_unit"));
		normalizeCurrentQuantity(body, inventoryMapper.productPack(productKey(stored)));
		BigDecimal unitCost = decimal(body.get("unit_cost"));
		BigDecimal after = (BigDecimal) body.get("current_base_qty");
		if (unitCost.signum() < 0 || (after.signum() > 0 && unitCost.signum() == 0))
			throw new IllegalArgumentException("기초재고 단가(기준단위당)를 0보다 크게 입력해주세요.");
		BigDecimal amountAfter = after.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
		try {
			saveValued(body, stored, "OPENING", decimal(stored.get("current_base_qty")), after, unitCost,
					decimal(stored.get("inventory_amount")), amountAfter, "OPENING", String.valueOf(stored.get("inventory_balance_id")));
		} catch (org.springframework.dao.DuplicateKeyException e) {
			throw new IllegalArgumentException("이 재고의 기초재고는 이미 등록되었습니다.");
		}
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
		movement.put("account_ingredient_product_id", stored.get("account_ingredient_product_id"));
		movement.put("location_id", body.get("location_id") != null ? body.get("location_id") : stored.get("location_id"));
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

	private Map<String, Object> productKey(Map<String, Object> stored) {
		Map<String, Object> key = new java.util.HashMap<>();
		key.put("account_ingredient_product_id", stored.get("account_ingredient_product_id"));
		return key;
	}

	private static BigDecimal decimal(Object value) {
		return value == null || value.toString().isBlank() ? BigDecimal.ZERO : new BigDecimal(value.toString());
	}

	/**
	 * 입력 수량을 재고 기준단위로 환산한다. kg↔g, L↔ml 외에 상품 발주단위도 받는다
	 * (예: 계란 2 PAC → 이 거래처 연결 규격 1 PAC = 30 EA 로 60 EA). 발주단위로 입력하면 표시 단위는 기준단위로 저장한다.
	 */
	void normalizeCurrentQuantity(Map<String, Object> body, Map<String, Object> pack) {
		if (body.get("current_qty") == null)
			return;
		String base = body.get("base_unit") == null ? "" : body.get("base_unit").toString();
		String current = body.get("current_unit") == null || body.get("current_unit").toString().isBlank() ? base
				: body.get("current_unit").toString();
		BigDecimal qty = new BigDecimal(body.get("current_qty").toString());
		String orderUnit = pack == null || pack.get("order_unit") == null ? "" : pack.get("order_unit").toString();
		BigDecimal packQty = pack == null || pack.get("pack_qty") == null ? BigDecimal.ZERO
				: new BigDecimal(pack.get("pack_qty").toString());
		String displayUnit = current;
		if (!orderUnit.isBlank() && QuantityUnits.unit(current).equals(QuantityUnits.unit(orderUnit))
				&& !QuantityUnits.unit(current).equals(QuantityUnits.unit(base)) && packQty.signum() > 0) {
			qty = QuantityUnits.convert(qty.multiply(packQty), pack.get("pack_unit"), base);
			displayUnit = base;
		} else {
			try {
				qty = QuantityUnits.convert(qty, current, base);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("'" + current + "' 단위는 이 재고의 기준단위(" + base
						+ ")로 환산할 수 없습니다. 입력 가능한 단위: " + String.join(", ", allowedUnits(base, orderUnit, packQty)));
			}
		}
		if (qty.signum() < 0)
			throw new IllegalArgumentException("재고는 음수일 수 없습니다.");
		body.put("current_base_qty", qty);
		body.put("current_unit", displayUnit);
	}

	/** 재고 입력 화면에서 고를 수 있는 단위: 기준단위, 같은 차원의 큰 단위, 상품 발주단위(규격이 있을 때) */
	static java.util.List<String> allowedUnits(String base, String orderUnit, BigDecimal packQty) {
		java.util.List<String> units = new java.util.ArrayList<>();
		String b = QuantityUnits.unit(base);
		units.add(b);
		if (b.equals("g"))
			units.add("kg");
		if (b.equals("ml"))
			units.add("L");
		if (!orderUnit.isBlank() && packQty != null && packQty.signum() > 0 && !units.contains(QuantityUnits.unit(orderUnit)))
			units.add(QuantityUnits.unit(orderUnit));
		return units;
	}

	public Map<String, Object> delete(Map<String, Object> body) {
		require(body, "inventory_balance_id");
		if (inventoryMapper.deleteInventory(body) == 0)
			throw new IllegalArgumentException("Inventory was not found.");
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
		body.put("account_ingredient_product_id", current.get("account_ingredient_product_id"));
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
