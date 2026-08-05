package com.example.demo.mapper;

import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoginMapper {
	
	String NowDateKey();
	Map<String, Object> Login(Map<String, Object> paramMap);					// 로그인
	Map<String, Object> AccountCoordinateInfo(Map<String, Object> paramMap);	// 근무지 좌표 조회
	int CommuteSave(Map<String, Object> paramMap);								// 출퇴근 저장
}
