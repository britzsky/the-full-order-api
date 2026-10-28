package com.example.demo.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.example.demo.mapper.MealRecipeRefreshMapper;

/**
 * 거래처 레시피(이 업장 레시피)를 바꾸면, 그 메뉴가 든 저장된 식단 중 오늘 이후·사용 확정 전 식사의 식재료 필요량·원가를 새 레시피로 다시 계산한다.
 * 지난 식사·사용 확정한 식사는 그때 쓴 양 그대로(재고 이력·차감·사용량 기록 유지). 2026-10-28 사용자 결정.
 * 컨트롤러에서 부른다(레시피 서비스 ↔ 식단 서비스 순환 의존을 만들지 않으려고).
 */
@Service
public class MealRecipeRefreshService {
	private static final Logger log = LoggerFactory.getLogger(MealRecipeRefreshService.class);
	private final MealRecipeRefreshMapper mapper;
	private final MealPlanV2Service plans;

	public MealRecipeRefreshService(MealRecipeRefreshMapper mapper, MealPlanV2Service plans) {
		this.mapper = mapper;
		this.plans = plans;
	}

	/** 다시 계산한 식사 수. 한 식사가 실패해도(레시피 단위 문제 등) 나머지는 계속하고, 실패한 식사는 예전 값 그대로 둔다 */
	public int refresh(Object accountId, Object menuId) {
		if (accountId == null || menuId == null || accountId.toString().isBlank() || menuId.toString().isBlank()) return 0;
		int done = 0;
		for (Long serviceId : mapper.targets(Map.of("account_id", accountId, "menu_id", menuId))) {
			Map<String, Object> request = new HashMap<>();
			request.put("meal_service_id", serviceId);
			request.put("account_id", accountId);
			try {
				plans.recalculate(request);
				done++;
			} catch (RuntimeException e) {
				log.warn("레시피 변경 후 식사 다시 계산 실패 account={} menu={} service={}: {}", accountId, menuId, serviceId, e.getMessage());
			}
		}
		return done;
	}

	/** 레시피 저장 응답에 다시 계산한 식사 수를 붙인다 */
	public Map<String, Object> withRefresh(Object result, Object accountId, Object menuId) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (result instanceof Map<?, ?> map) map.forEach((k, v) -> out.put(String.valueOf(k), v));
		out.put("refreshed_meal_count", refresh(accountId, menuId));
		return out;
	}
}
