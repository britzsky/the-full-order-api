package com.example.demo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.AnalyticsMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AnalyticsService {
	private final AnalyticsMapper analyticsMapper;
	private final ObjectMapper objectMapper;

	public AnalyticsService(AnalyticsMapper analyticsMapper, ObjectMapper objectMapper) {
		this.analyticsMapper = analyticsMapper;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public Map<String, Object> saveSearch(Map<String, Object> params) {
		requireText(params, "query_raw");
		prepareSession(params, "SEARCH");
		params.put("query_normalized", normalize(text(params.get("query_raw"))));
		params.putIfAbsent("intent_type", "UNKNOWN");
		params.put("filters_json", json(params.get("filters")));
		analyticsMapper.insertSearchEvent(params);

		Object rawResults = params.get("results");
		if (rawResults instanceof List<?> results) {
			int position = 0;
			for (Object rawResult : results) {
				if (!(rawResult instanceof Map<?, ?> result)) {
					continue;
				}
				Map<String, Object> action = new java.util.HashMap<>();
				action.put("search_event_id", params.get("search_event_id"));
				action.put("search_session_id", params.get("search_session_id"));
				action.put("account_id", params.get("account_id"));
				action.put("entity_type", result.get("entity_type"));
				action.put("entity_id", result.get("entity_id"));
				action.put("action_type", "IMPRESSION");
				action.put("screen", params.get("screen"));
				action.put("position_no", result.get("position_no") == null ? ++position : result.get("position_no"));
				action.put("context_json", json(result.get("context")));
				analyticsMapper.insertSearchAction(action);
			}
		}
		return Map.of("search_event_id", params.get("search_event_id"), "search_session_id",
				params.get("search_session_id"));
	}

	@Transactional
	public void saveAction(Map<String, Object> params) {
		requireText(params, "action_type");
		prepareSession(params, "ACTION");
		params.put("context_json", json(params.get("context")));
		analyticsMapper.insertSearchAction(params);
	}

	public List<Map<String, Object>> popularSearches(String accountId, int days, int limit) {
		return analyticsMapper.popularSearches(Map.of("account_id", accountId == null ? "" : accountId, "days",
				Math.max(1, Math.min(days, 365)), "limit", Math.max(1, Math.min(limit, 50))));
	}

	public Map<String, Object> searchDashboard(String accountId, int days, int limit) {
		Map<String, Object> query = Map.of("account_id", accountId == null ? "" : accountId, "days",
				Math.max(1, Math.min(days, 365)), "limit", Math.max(1, Math.min(limit, 50)));
		return Map.of("summary", analyticsMapper.searchSummary(query), "popular_searches",
				analyticsMapper.popularSearches(query), "no_result_searches", analyticsMapper.noResultSearches(query),
				"intent_summary", analyticsMapper.intentSummary(query));
	}

	@Transactional
	public String saveRecommendation(Map<String, Object> params) {
		requireText(params, "recommendation_type");
		String recommendationId = text(params.get("recommendation_id"));
		if (recommendationId.isBlank()) {
			recommendationId = UUID.randomUUID().toString();
			params.put("recommendation_id", recommendationId);
		}
		String sessionId = text(params.get("search_session_id"));
		if (!sessionId.isBlank()) {
			prepareSession(params, "AI_CHAT");
		}
		params.put("user_id_hash", hash(text(params.get("user_id"))));
		params.putIfAbsent("model_version", "rule-v1");
		params.put("request_context_json", json(params.get("request_context")));
		analyticsMapper.insertRecommendationEvent(params);

		Object rawItems = params.get("items");
		if (rawItems instanceof List<?> items) {
			int rank = 0;
			for (Object rawItem : items) {
				if (!(rawItem instanceof Map<?, ?> item)) {
					continue;
				}
				Map<String, Object> row = new java.util.HashMap<>();
				row.put("recommendation_id", recommendationId);
				row.put("entity_type", item.get("entity_type"));
				row.put("entity_id", item.get("entity_id"));
				row.put("rank_no", item.get("rank_no") == null ? ++rank : item.get("rank_no"));
				row.put("score", item.get("score"));
				row.put("reason_json", json(item.get("reason")));
				analyticsMapper.insertRecommendationItem(row);
			}
		}
		return recommendationId;
	}

	public int saveRecommendationFeedback(Map<String, Object> params) {
		requireText(params, "recommendation_id");
		requireText(params, "entity_type");
		requireText(params, "entity_id");
		requireText(params, "accepted_yn");
		return analyticsMapper.updateRecommendationFeedback(params);
	}

	public List<Map<String, Object>> hygieneGuides(Map<String, Object> params) {
		params.putIfAbsent("category", "");
		params.putIfAbsent("situation_code", "");
		return analyticsMapper.hygieneGuides(params);
	}

	public String saveHygieneIncident(Map<String, Object> params) {
		requireText(params, "account_id");
		requireText(params, "incident_type");
		String incidentId = text(params.get("incident_id"));
		if (incidentId.isBlank()) {
			incidentId = UUID.randomUUID().toString();
			params.put("incident_id", incidentId);
		}
		analyticsMapper.insertHygieneIncident(params);
		return incidentId;
	}

	public long saveHygieneAction(Map<String, Object> params) {
		requireText(params, "incident_id");
		requireText(params, "recommended_action");
		analyticsMapper.insertHygieneAction(params);
		Object id = params.get("hygiene_action_id");
		return id instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(id));
	}

	public int completeHygieneAction(Map<String, Object> params) {
		requireText(params, "hygiene_action_id");
		requireText(params, "completed_yn");
		return analyticsMapper.completeHygieneAction(params);
	}

	private void prepareSession(Map<String, Object> params, String defaultChannel) {
		String sessionId = text(params.get("search_session_id"));
		if (sessionId.isBlank()) {
			sessionId = UUID.randomUUID().toString();
			params.put("search_session_id", sessionId);
		}
		params.putIfAbsent("account_id", "");
		params.putIfAbsent("channel", defaultChannel);
		params.put("user_id_hash", hash(text(params.get("user_id"))));
		analyticsMapper.upsertSearchSession(params);
	}

	private String json(Object value) {
		if (value == null) {
			return "";
		}
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("context must be JSON serializable", e);
		}
	}

	private String normalize(String value) {
		return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	private String hash(String value) {
		if (value.isBlank()) {
			return "";
		}
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private void requireText(Map<String, Object> params, String key) {
		if (text(params.get(key)).isBlank()) {
			throw new IllegalArgumentException(key + " is required.");
		}
	}

	private String text(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}
}
