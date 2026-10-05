package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnalyticsMapper {
	int upsertSearchSession(Map<String, Object> params);

	int insertSearchEvent(Map<String, Object> params);

	int insertSearchAction(Map<String, Object> params);

	List<Map<String, Object>> popularSearches(Map<String, Object> params);

	Map<String, Object> searchSummary(Map<String, Object> params);

	List<Map<String, Object>> noResultSearches(Map<String, Object> params);

	List<Map<String, Object>> intentSummary(Map<String, Object> params);

	int insertRecommendationEvent(Map<String, Object> params);

	int insertRecommendationItem(Map<String, Object> params);

	int updateRecommendationFeedback(Map<String, Object> params);

	List<Map<String, Object>> hygieneGuides(Map<String, Object> params);

	int insertHygieneIncident(Map<String, Object> params);

	int insertHygieneAction(Map<String, Object> params);

	int completeHygieneAction(Map<String, Object> params);
}
