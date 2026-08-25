package com.example.demo.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.WebConfig;
import com.example.demo.service.AccountService;
import com.google.gson.Gson;

@RestController
public class AccountController {

	private final AccountService accountService;
	@Autowired
	public AccountController(
			AccountService accountService,
			WebConfig webConfig,
			@Value("${file.upload-dir}") String uploadDir) {
		this.accountService = accountService;
	}

	/*
	 * method : AccountList
	 * comment : 거래처 조회
	 */
	@GetMapping("/Account/AccountList")
	public String AccountList(@RequestParam Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		//int iAccountType = Integer.parseInt(paramMap.get("account_type").toString());
		resultList = accountService.AccountList(paramMap);

		return new Gson().toJson(resultList);
	}

	/*
	 * method : MealSlotList
	 * comment : 고객사별 식사구분 설정 조회
	 */
	@GetMapping("/Account/MealSlotList")
	public String MealSlotList(@RequestParam Map<String, Object> paramMap) {
		return new Gson().toJson(accountService.MealSlotList(paramMap));
	}

	/*
	 * method : MealSlotSave
	 * comment : 고객사별 식사구분 설정 저장
	 */
	@PostMapping("/Account/MealSlotSave")
	public String MealSlotSave(@RequestBody Map<String, Object> paramMap) {
		accountService.MealSlotSave(paramMap);
		return new Gson().toJson(Map.of("result", "success"));
	}

	/*
	 * method : MealConfiguration
	 * comment : 고객사 유형별 자동 계산 단가와 식사구분별 기본 식사 구성을 함께 조회
	 */
	@GetMapping("/Account/MealConfiguration")
	public String MealConfiguration(@RequestParam Map<String, Object> paramMap) {
		return new Gson().toJson(accountService.MealConfiguration(paramMap));
	}

	/*
	 * method : MealConfigurationSave
	 * comment : 급식형식과 자동 식단 기본값을 한 트랜잭션으로 저장
	 */
	@PostMapping("/Account/MealConfigurationSave")
	public String MealConfigurationSave(@RequestBody Map<String, Object> paramMap) {
		accountService.MealConfigurationSave(paramMap);
		return new Gson().toJson(Map.of("result", "success"));
	}
}
