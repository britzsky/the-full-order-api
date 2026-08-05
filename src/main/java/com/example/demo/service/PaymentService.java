package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.demo.mapper.PaymentMapper;

@Service
public class PaymentService {

	PaymentMapper paymentMapper;
	
	public PaymentService(PaymentMapper paymentMapper) {
		this.paymentMapper = paymentMapper;
	}
	
	public List<Map<String, Object>> SubscriptionStatus(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = paymentMapper.SubscriptionStatus(paramMap);
		return resultList;
	}
}
