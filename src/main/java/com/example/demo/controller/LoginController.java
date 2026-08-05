package com.example.demo.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.WebConfig;
import com.example.demo.service.LoginService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

@RestController
public class LoginController {

	private final LoginService loginService;
	private final String uploadDir;

	@Autowired
	public LoginController(
			LoginService loginService,
			WebConfig webConfig,
			@Value("${file.upload-dir}") String uploadDir) {
		this.loginService = loginService;
		this.uploadDir = uploadDir;
	}

	/*
	 * method : Login
	 * comment : 로그인
	 */
	@PostMapping("/User/Login")
	public String Login(@RequestBody HashMap<String, Object> map) {
		
		Map<String, Object> resultMap = loginService.Login(map);
		JsonObject obj = new JsonObject();

		// null 안전 처리
		String statusCode = "400";
		if (resultMap != null && resultMap.get("status_code") != null) {
			statusCode = String.valueOf(resultMap.get("status_code"));
		}

		// ✅ 1) 아이디/비번 실패 OR ✅ 2) 미승인(use_yn='N') 차단
		if (!"200".equals(statusCode)) {
			obj.addProperty("code", statusCode);

			if ("400".equals(statusCode)) {
				obj.addProperty("msg", "아이디 혹은 비밀번호를 확인하세요.");
			} else {
				obj.addProperty("msg", "승인되지 않은 계정입니다. 관리자에게 문의해주세요.");
			}
			return obj.toString();
		}

		// ===== 성공 응답 =====
		obj.addProperty("user_id", String.valueOf(resultMap.get("user_id")));
		obj.addProperty("user_type", String.valueOf(resultMap.get("user_type")));
		obj.addProperty("position", String.valueOf(resultMap.get("position")));
		
		String user_id = String.valueOf(resultMap.get("user_id"));
		int position = Integer.parseInt(String.valueOf(resultMap.get("position")));

		if ("ceo".equals(user_id)) {
			obj.addProperty("position_name", "CEO");
		} else if ("britzsky".equals(user_id) || "a12".equals(user_id) || "mh2".equals(user_id) || "bh4".equals(user_id)
				|| "yh2".equals(user_id)) {
			obj.addProperty("position_name", "Team Leader");
		} else if ("sy7".equals(user_id) || "jr1".equals(user_id)) {
			obj.addProperty("position_name", "Part Leader");
		} else if (position == 8) {
			obj.addProperty("position_name", "Dietitian");
		} else {
			obj.addProperty("position_name", "Manager");
		}
		
		obj.addProperty("department", String.valueOf(resultMap.get("department")));
		obj.addProperty("account_id", String.valueOf(resultMap.get("account_id")));
		obj.addProperty("user_name", String.valueOf(resultMap.get("user_name")));

		obj.addProperty("code", statusCode);

		return obj.toString();
	}
	
	/* 
	 * part		: 근태관리
     * method 	: AccountCoordinateInfo
     * comment 	: 근무지 좌표 조회.
     */
	@PostMapping("/User/AccountCoordinateInfo")
    private String AccountCoordinateInfo(@RequestBody Map<String, Object> paramMap) {
    	Map<String, Object> resultMap = new HashMap<String, Object>();
    	resultMap = loginService.AccountCoordinateInfo(paramMap);
    	
    	return new Gson().toJson(resultMap);
    }
	
	/*
	 * part		: 근태관리
	 * method : CommuteSave
	 * comment : 출퇴근 저장
	 */
	@PostMapping("/User/CommuteSave")
	public String ApprovalSave(@RequestBody Map<String, Object> paramMap) {
		int iResult = 0;
    	
		iResult = loginService.CommuteSave(paramMap);
    	
    	JsonObject obj = new JsonObject();
    	
    	if(iResult > 0) {
			obj.addProperty("code", 200);
			obj.addProperty("message", "성공");
    	} else {
    		obj.addProperty("code", 400);
			obj.addProperty("message", "실패");
    	}
    	
    	return obj.toString();
	}
}
