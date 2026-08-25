package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.AccountMapper;

@Service
public class AccountService {

	AccountMapper accountMapper;
	
	public AccountService(AccountMapper accountMapper) {
		this.accountMapper = accountMapper;
	}
	
	// 거래처 -> 거래처 목록 조회
	public List<Map<String, Object>> AccountList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = accountMapper.AccountList(paramMap);
		return resultList;
	}

	/*
	 * method : MealSlotList
	 * comment : 고객사에서 선택 가능한 전체 식사구분과 현재 설정 조회
	 */
	public List<Map<String, Object>> MealSlotList(Map<String, Object> paramMap) {
		return accountMapper.MealSlotList(paramMap);
	}

	/*
	 * method : MealSlotSave
	 * comment : 고객사의 기존 식사구분 설정을 선택값으로 일괄 갱신
	 */
	@Transactional
	public void MealSlotSave(Map<String, Object> paramMap) {
		accountMapper.DeleteMealSlotSetting(paramMap);
		Object mealSlots = paramMap.get("meal_slots");
		if (mealSlots instanceof List<?> slots && !slots.isEmpty()) {
			accountMapper.InsertMealSlotSetting(paramMap);
		}
	}

	/*
	 * 고객사 기본 구성과 서버 계산 단가를 화면에서 바로 사용할 수 있는 형태로 묶는다.
	 * 단가는 DB 뷰가 the_full.tb_account/account_info의 최신 값을 기준으로 계산한다.
	 */
	public Map<String, Object> MealConfiguration(Map<String, Object> paramMap) {
		Object accountId = paramMap.get("account_id");
		if (accountId == null || accountId.toString().isBlank()) {
			throw new IllegalArgumentException("account_id is required.");
		}
		List<Map<String, Object>> rows = accountMapper.MealConfiguration(paramMap);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("account_id", accountId);
		if (rows.isEmpty()) {
			result.put("account_type", 0);
			result.put("account_type_name", "미설정");
			result.put("price_source", "UNSUPPORTED");
			result.put("main_meal_count", 0);
			result.put("profiles", rows);
			return result;
		}
		Map<String, Object> first = rows.get(0);
		result.put("account_type", first.get("account_type"));
		result.put("account_type_name", first.get("account_type_name"));
		result.put("price_source", first.get("budget_source"));
		result.put("price_source_amount", first.get("budget_source_amount"));
		result.put("main_meal_count", first.get("main_meal_count"));
		result.put("profiles", rows);
		return result;
	}

	/*
	 * 급식형식과 기본 구성을 원자적으로 저장한다.
	 * 중간 실패 시 식사구분만 저장되는 불완전한 상태가 남지 않도록 반드시 한 트랜잭션으로 처리한다.
	 */
	@Transactional
	public void MealConfigurationSave(Map<String, Object> paramMap) {
		Object accountId = paramMap.get("account_id");
		Object mealSlots = paramMap.get("meal_slots");
		if (accountId == null || accountId.toString().isBlank()) {
			throw new IllegalArgumentException("account_id is required.");
		}
		if (!(mealSlots instanceof List<?> slots) || slots.isEmpty()) {
			throw new IllegalArgumentException("At least one meal slot is required.");
		}
		accountMapper.DeleteMealSlotProfile(paramMap);
		accountMapper.DeleteMealSlotSetting(paramMap);
		accountMapper.InsertMealSlotSetting(paramMap);
		accountMapper.InsertMealSlotProfile(paramMap);
	}
}
