package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

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
}
