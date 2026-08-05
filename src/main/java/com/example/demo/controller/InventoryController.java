package com.example.demo.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.WebConfig;
import com.example.demo.service.InventoryService;
import com.google.gson.Gson;

@RestController
public class InventoryController {

	private final InventoryService inventoryService;
	@Autowired
	public InventoryController(
			InventoryService inventoryService,
			WebConfig webConfig,
			@Value("${file.upload-dir}") String uploadDir) {
		this.inventoryService = inventoryService;
	}

	/*
	 * method : AccountInventoryList
	 * comment : 거래처 재고 조회
	 */
	@GetMapping("/Inventory/AccountInventoryList")
	public String AccountList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		//int iAccountType = Integer.parseInt(paramMap.get("account_type").toString());
		resultList = inventoryService.AccountInventoryList(paramMap);

		return new Gson().toJson(resultList);
	}
}
