package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.InventoryMapper;

@Service
public class InventoryService {

	InventoryMapper inventoryMapper;
	
	public InventoryService(InventoryMapper inventoryMapper) {
		this.inventoryMapper = inventoryMapper;
	}
	
	// 재고관리 -> 거래처 재고 조회
	public List<Map<String, Object>> AccountInventoryList(Map<String, Object> paramMap) {
		require(paramMap, "account_id");
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = inventoryMapper.AccountInventoryList(paramMap);
		return resultList;
	}
	public List<Map<String,Object>> movements(Map<String,Object> params) { return inventoryMapper.movements(params); }
	public Map<String,Object> create(Map<String,Object> body) { require(body,"account_id","account_ingredient_product_id","base_unit"); normalizeCurrentQuantity(body); inventoryMapper.insertInventory(body); return ok(body.get("inventory_balance_id")); }
	@Transactional
	public Map<String,Object> update(Map<String,Object> body) {
		require(body,"inventory_balance_id");
		normalizeCurrentQuantity(body);
		if(inventoryMapper.updateInventory(body)==0) throw new IllegalArgumentException("Inventory was not found.");
		if(body.get("account_ingredient_product_id")!=null && body.get("safe_stock_base_qty")!=null) {
			inventoryMapper.updateAccountProduct(body);
		}
		return ok(body.get("inventory_balance_id"));
	}
	private void normalizeCurrentQuantity(Map<String,Object> body) {
		if(body.get("current_qty")==null) return;
		String base=body.get("base_unit")==null ? "" : body.get("base_unit").toString();
		String current=body.get("current_unit")==null ? base : body.get("current_unit").toString();
		BigDecimal qty=new BigDecimal(body.get("current_qty").toString());
		if((base.equalsIgnoreCase("g") && current.equalsIgnoreCase("kg")) || (base.equalsIgnoreCase("ml") && current.equalsIgnoreCase("l"))) qty=qty.multiply(BigDecimal.valueOf(1000));
		else if(!base.equalsIgnoreCase(current)) throw new IllegalArgumentException("current_unit is not compatible with base_unit.");
		body.put("current_base_qty",qty);
		body.put("current_unit",current);
	}
	public Map<String,Object> delete(Map<String,Object> body) { require(body,"inventory_balance_id"); if(inventoryMapper.deleteInventory(body)==0) throw new IllegalArgumentException("Inventory was not found."); return ok(body.get("inventory_balance_id")); }
	@Transactional public Map<String,Object> move(Map<String,Object> body) {
		require(body,"inventory_balance_id","movement_type","quantity_delta"); Map<String,Object> current=inventoryMapper.inventoryForUpdate(body); if(current==null) throw new IllegalArgumentException("Inventory was not found.");
		BigDecimal before=new BigDecimal(current.get("current_base_qty").toString()); BigDecimal delta=new BigDecimal(body.get("quantity_delta").toString()); BigDecimal after=before.add(delta); if(after.signum()<0) throw new IllegalArgumentException("Inventory cannot be negative.");
		body.put("account_id",current.get("account_id")); body.put("account_ingredient_product_id",current.get("account_ingredient_product_id")); body.put("location_id",current.get("location_id")); body.put("base_unit",current.get("base_unit")); body.put("quantity_before",before); body.put("quantity_after",after); body.put("movement_id",UUID.randomUUID().toString()); body.put("current_base_qty",after);
		inventoryMapper.updateInventory(body); inventoryMapper.insertMovement(body); return ok(body.get("movement_id"));
	}
	private void require(Map<String,Object>b,String...fs){for(String f:fs)if(b.get(f)==null||b.get(f).toString().isBlank())throw new IllegalArgumentException(f+" is required.");}
	private Map<String,Object>ok(Object id){return Map.of("code",200,"message","success","id",id);}
}
