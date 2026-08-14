package com.example.demo.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;

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
	@GetMapping("/v2/inventory") public ResponseEntity<?> list(@RequestParam Map<String,Object> p){return execute(()->inventoryService.AccountInventoryList(p));}
	@PostMapping("/v2/inventory") public ResponseEntity<?> create(@RequestBody Map<String,Object>b){return execute(()->inventoryService.create(b));}
	@PatchMapping("/v2/inventory") public ResponseEntity<?> update(@RequestBody Map<String,Object>b){return execute(()->inventoryService.update(b));}
	@DeleteMapping("/v2/inventory") public ResponseEntity<?> delete(@RequestBody Map<String,Object>b){return execute(()->inventoryService.delete(b));}
	@GetMapping("/v2/inventory/movements") public ResponseEntity<?> movements(@RequestParam Map<String,Object>p){return execute(()->inventoryService.movements(p));}
	@PostMapping("/v2/inventory/movements") public ResponseEntity<?> move(@RequestBody Map<String,Object>b){return execute(()->inventoryService.move(b));}
	private ResponseEntity<?> execute(java.util.function.Supplier<?> a){try{return ResponseEntity.ok(a.get());}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("code",400,"message",e.getMessage()));}}
}
