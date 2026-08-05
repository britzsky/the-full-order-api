package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountMapper {
	
	String NowDateKey();
	List<Map<String, Object>> AccountList(Map<String, Object> paramMap);
}
