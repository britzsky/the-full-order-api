package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
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
}
