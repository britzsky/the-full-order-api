package com.example.demo.security;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 나만의 메뉴·식재료의 주인(2026-10-22 사용자 결정): 현장 계정은 거래처마다 하나(ACCOUNT, account_id),
 * 본사 계정은 사용자마다 하나(USER, user_id). 로그인 세션으로 정하고, 세션이 없으면(내부 호출·테스트) 요청의 account_id를 쓴다.
 */
public record FavoriteOwner(String type, String id) {
	public static FavoriteOwner of(HttpServletRequest request, Map<String, Object> params) {
		var session = request == null ? null : request.getSession(false);
		Object value = session == null ? null : session.getAttribute(WorkspaceUser.SESSION_KEY);
		if (value instanceof WorkspaceUser user) {
			if (user.headquarters() && !user.userId().isBlank())
				return new FavoriteOwner("USER", user.userId());
			if (!user.accountId().isBlank())
				return new FavoriteOwner("ACCOUNT", user.accountId());
		}
		String account = params == null || params.get("account_id") == null ? "" : params.get("account_id").toString().trim();
		if (account.isEmpty())
			throw new IllegalArgumentException("거래처 선택 요망");
		return new FavoriteOwner("ACCOUNT", account);
	}

	/** 매퍼 파라미터로 넣는다(favorite_owner_type, favorite_owner_id). */
	public Map<String, Object> applyTo(Map<String, Object> params) {
		params.put("favorite_owner_type", type);
		params.put("favorite_owner_id", id);
		return params;
	}
}
