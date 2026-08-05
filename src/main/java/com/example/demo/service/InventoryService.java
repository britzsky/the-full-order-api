package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.demo.mapper.InventoryMapper;

@Service
public class InventoryService {

	InventoryMapper inventoryMapper;
	
	public InventoryService(InventoryMapper inventoryMapper) {
		this.inventoryMapper = inventoryMapper;
	}
	
	// 재고관리 -> 거래처 재고 조회
	public List<Map<String, Object>> AccountInventoryList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = inventoryMapper.AccountInventoryList(paramMap);
		return resultList;
	}
}
