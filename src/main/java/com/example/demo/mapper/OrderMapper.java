package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper {
	
	String NowDateKey();
	List<Map<String, Object>> MenuList(Map<String, Object> paramMap);			// 메뉴 조회
	List<Map<String, Object>> DetailList(Map<String, Object> paramMap);			// 메뉴 식자재 조회
}
