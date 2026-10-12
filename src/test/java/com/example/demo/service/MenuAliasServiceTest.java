package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.example.demo.mapper.MenuAliasMapper;

class MenuAliasServiceTest {
	private final MenuAliasMapper mapper = mock(MenuAliasMapper.class);
	private final MenuAliasService service = new MenuAliasService(mapper);

	@Test
	void keyDropsAllergenNumbersSymbolsAndSpaces() {
		assertThat(MenuNames.key("소고기 미역국(5.6.16)")).isEqualTo("소고기미역국");
		assertThat(MenuNames.key("★소고기미역국 5.6.16")).isEqualTo("소고기미역국");
		assertThat(MenuNames.key("소고기미역국[13]")).isEqualTo("소고기미역국");
		assertThat(MenuNames.key("3색나물")).as("메뉴명 안의 숫자는 유지").isEqualTo("3색나물");
		assertThat(MenuNames.key("ＡＢＣ 샐러드")).isEqualTo("abc샐러드");
	}

	@Test
	void savesOnlyAliasesThatDifferFromMenuName() {
		when(mapper.activeMenuName(anyMap())).thenReturn("쇠고기미역국");
		var result = service.save(new HashMap<>(Map.of("account_id", "A", "user_id", "u", "aliases", List.of(
				Map.of("alias_name", "소고기미역국(5.6)", "menu_id", "M1"),
				Map.of("alias_name", "쇠고기 미역국", "menu_id", "M1")))));
		assertThat(result).containsEntry("saved_count", 1).containsEntry("skipped_count", 1);
		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass(Map.class);
		verify(mapper).upsertAlias(saved.capture());
		assertThat(saved.getValue()).containsEntry("alias_key", "소고기미역국").containsEntry("alias_name", "소고기미역국(5.6)").containsEntry("menu_id", "M1");
	}

	@Test
	void rejectsMenuOutsideAccount() {
		when(mapper.activeMenuName(anyMap())).thenReturn(null);
		assertThatThrownBy(() -> service.save(new HashMap<>(Map.of("account_id", "A", "aliases", List.of(Map.of("alias_name", "잡채", "menu_id", "X"))))))
				.hasMessageContaining("등록된 메뉴가 아닙니다");
		verify(mapper, never()).upsertAlias(anyMap());
	}
}
