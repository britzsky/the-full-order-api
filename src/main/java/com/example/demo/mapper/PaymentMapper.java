package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentMapper {
	
	String NowDateKey();
	List<Map<String, Object>> PlanList(Map<String, Object> paramMap);				// 요금제 조회
	List<Map<String, Object>> SubscriptionStatus(Map<String, Object> paramMap);		// 구독상태 식자재 조회
}
