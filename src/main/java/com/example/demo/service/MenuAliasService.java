package com.example.demo.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.MenuAliasMapper;

/**
 * 거래처 메뉴 별칭. 식단표 PDF의 메뉴 표기가 거래처 메뉴명과 다를 때 사람이 한 번 고른 연결을 기억한다.
 * 유사도만으로는 저장하지 않는다(사람이 고른 연결만). 메뉴명과 같은 키는 별칭이 필요 없으므로 건너뛴다.
 */
@Service
public class MenuAliasService {
	private final MenuAliasMapper mapper;

	public MenuAliasService(MenuAliasMapper mapper) {
		this.mapper = mapper;
	}

	public List<Map<String, Object>> aliases(Map<String, Object> p) {
		require(p, "account_id");
		return mapper.aliases(p);
	}

	@Transactional
	public Map<String, Object> save(Map<String, Object> b) {
		require(b, "account_id");
		if (!(b.get("aliases") instanceof List<?> rows) || rows.isEmpty())
			throw new IllegalArgumentException("저장할 메뉴 연결이 없습니다.");
		int saved = 0, skipped = 0;
		for (Object value : rows) {
			if (!(value instanceof Map<?, ?> row))
				throw new IllegalArgumentException("메뉴 연결 형식을 확인해주세요.");
			String aliasName = Objects.toString(row.get("alias_name"), "").strip();
			String menuId = Objects.toString(row.get("menu_id"), "").strip();
			Map<String, Object> p = new HashMap<>();
			p.put("account_id", b.get("account_id"));
			p.put("user_id", b.get("user_id"));
			p.put("menu_id", menuId);
			p.put("alias_name", aliasName.length() > 255 ? aliasName.substring(0, 255) : aliasName);
			p.put("alias_key", MenuNames.key(aliasName));
			if (menuId.isEmpty() || p.get("alias_key").toString().length() < 2)
				throw new IllegalArgumentException("메뉴 표기와 연결할 메뉴를 확인해주세요: " + aliasName);
			String menuName = mapper.activeMenuName(p);
			if (menuName == null)
				throw new IllegalArgumentException("이 거래처에 등록된 메뉴가 아닙니다: " + menuId);
			if (MenuNames.key(menuName).equals(p.get("alias_key"))) {
				skipped++;
				continue;
			}
			mapper.upsertAlias(p);
			saved++;
		}
		return Map.of("code", 200, "message", "success", "saved_count", saved, "skipped_count", skipped);
	}

	public Map<String, Object> delete(Map<String, Object> b) {
		require(b, "account_id", "alias_id");
		if (mapper.deleteAlias(b) == 0)
			throw new IllegalArgumentException("메뉴 연결을 찾을 수 없습니다.");
		return Map.of("code", 200, "message", "success");
	}

	private static void require(Map<String, Object> b, String... fields) {
		for (String f : fields)
			if (b.get(f) == null || b.get(f).toString().isBlank())
				throw new IllegalArgumentException(f + " is required.");
	}
}
