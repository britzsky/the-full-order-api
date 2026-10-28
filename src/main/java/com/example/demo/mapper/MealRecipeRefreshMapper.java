package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

/** 거래처 레시피 변경 뒤 다시 계산할 식사(오늘 이후·사용 확정 전) */
@Mapper
public interface MealRecipeRefreshMapper {
	List<Long> targets(Map<String, Object> p);
}
