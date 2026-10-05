package com.example.demo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShortageProcurementMapper {
	List<Map<String, Object>> demand(Map<String, Object> params);

	List<Map<String, Object>> draftDemand(Map<String, Object> params);

	List<Map<String, Object>> stock(Map<String, Object> params);

	List<Map<String, Object>> targets(Map<String, Object> params);

	List<Map<String, Object>> history(Map<String, Object> params);

	List<Map<String, Object>> incoming(Map<String, Object> params);
}
