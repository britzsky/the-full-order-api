package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 홈 검색과 거래처 메뉴 추천의 조회 조건을 DB에서 처리한다. */
@Mapper
public interface DiscoveryMapper {
	List<Map<String, Object>> search(Map<String, Object> params);

	List<Map<String, Object>> recommendedMenus(Map<String, Object> params);
}
