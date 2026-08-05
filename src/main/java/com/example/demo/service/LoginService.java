package com.example.demo.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.demo.mapper.LoginMapper;

@Service
public class LoginService {

	LoginMapper loginMapper;
	
	public LoginService(LoginMapper loginMapper) {
		this.loginMapper = loginMapper;
	}
	
	public String NowDateKey() {
		String accountKey = loginMapper.NowDateKey();
		return accountKey;
	}
	
	// 로그인
	public Map<String, Object> Login(Map<String, Object> paramMap) {
		return loginMapper.Login(paramMap);
	}
	
	// 근무지 좌표 조회
	public Map<String, Object> AccountCoordinateInfo(Map<String, Object> paramMap) {
		Map<String, Object> resultMap = new HashMap<String, Object>();
		resultMap = loginMapper.AccountCoordinateInfo(paramMap);
		return resultMap;
	}
	
	// 출퇴근 저장
	public int CommuteSave(Map<String, Object> paramMap) {
		return loginMapper.CommuteSave(paramMap);
	}
}
