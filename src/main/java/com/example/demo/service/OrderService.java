package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.demo.mapper.OrderMapper;

@Service
public class OrderService {

	OrderMapper orderMapper;
	
	public OrderService(OrderMapper orderMapper) {
		this.orderMapper = orderMapper;
	}
	
	public String NowDateKey() {
		String accountKey = orderMapper.NowDateKey();
		return accountKey;
	}
	
	public List<Map<String, Object>> MenuList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = orderMapper.MenuList(paramMap);
		return resultList;
	}
	
	public List<Map<String, Object>> DetailList(Map<String, Object> paramMap) {
		List<Map<String, Object>> resultList = new ArrayList<>();
		resultList = orderMapper.DetailList(paramMap);
		return resultList;
	}
}
