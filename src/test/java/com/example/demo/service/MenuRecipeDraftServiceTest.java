package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import com.example.demo.mapper.AccountMenuRegistrationMapper;
import com.example.demo.mapper.AiUsageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 미등록 메뉴 AI 레시피 초안: 거래처 식재료 이름 연결·단위 불일치 표시·중복 제거, 예상 비용 계산, 한도 */
@SuppressWarnings("unchecked")
class MenuRecipeDraftServiceTest {
    final AccountMenuRegistrationMapper mapper = mock(AccountMenuRegistrationMapper.class);
    final OpenAiClient openAi = mock(OpenAiClient.class);
    final MenuRecipeDraftService service = new MenuRecipeDraftService(mapper, openAi, new ObjectMapper());

    static Map<String, Object> ingredient(String id, String name, String unit) {
        return new HashMap<>(Map.of("ingredient_id", id, "ingredient_name", name, "base_unit", unit, "category_name", ""));
    }

    @Test void linksIngredientsByNameAndFlagsUnitMismatch() {
        when(mapper.accountIngredients(anyMap())).thenReturn(List.of(
            ingredient("PORK", "돼지고기(앞다리)", "g"), ingredient("EGG", "계란", "EA"), ingredient("ONION", "양파", "g")));
        when(openAi.model()).thenReturn("gpt-test");
        when(openAi.respond(any(), anyString(), anyString(), anyString(), anyMap())).thenReturn("""
            {"menus":[
              {"text":"제육볶음(5.10)","menu_name":"제육볶음","calories_per_serving":420,"ingredients":[
                {"name":"돼지고기 앞다리","qty":120,"unit":"g","category":"육류"},
                {"name":"양파","qty":30,"unit":"g","category":"채소"},
                {"name":"양파","qty":10,"unit":"g","category":"채소"},
                {"name":"고추장","qty":15,"unit":"g","category":"양념"}]},
              {"text":"계란찜","menu_name":"계란찜","calories_per_serving":90,"ingredients":[
                {"name":"계란","qty":60,"unit":"g","category":"난류"}]}
            ]}""");

        var result = service.draft(Map.of("account_id", "A", "menus", List.of(Map.of("text", "제육볶음(5.10)"), Map.of("text", "계란찜"))), "u");
        var drafts = (List<Map<String, Object>>) result.get("drafts");
        assertThat(drafts).hasSize(2);

        var jeyuk = (List<Map<String, Object>>) drafts.get(0).get("ingredients");
        assertThat(drafts.get(0)).containsEntry("menu_name", "제육볶음").containsEntry("calories_per_serving", 420);
        assertThat(jeyuk).extracting(r -> r.get("ingredient_id")).containsExactly("PORK", "ONION", null); // 양파 두 번째 줄은 빠짐
        assertThat(jeyuk.get(2)).containsEntry("ai_name", "고추장").containsEntry("unit_mismatch", false);

        var egg = ((List<Map<String, Object>>) drafts.get(1).get("ingredients")).get(0);
        assertThat(egg).containsEntry("ingredient_id", "EGG").containsEntry("unit_mismatch", true);
        verify(openAi).respond(argThat(c -> "A".equals(c.accountId()) && c.itemCount() == 2 && "MENU_RECIPE_DRAFT".equals(c.feature())),
            anyString(), argThat(p -> p.contains("돼지고기(앞다리)(g)")), anyString(), anyMap());
    }

    @Test void rejectsTooManyMenusAndEmptyInput() {
        assertThatThrownBy(() -> service.draft(Map.of("account_id", "A", "menus", List.of()), "u")).hasMessageContaining("메뉴를 선택");
        var many = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "메뉴" + i).toList();
        assertThatThrownBy(() -> service.draft(Map.of("account_id", "A", "menus", many), "u")).hasMessageContaining("10개");
        verifyNoInteractions(openAi);
    }

    @Test void aiFailureBecomesUserMessage() {
        when(mapper.accountIngredients(anyMap())).thenReturn(List.of());
        when(openAi.respond(any(), anyString(), anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("오늘 AI 사용 한도"));
        assertThatThrownBy(() -> service.draft(Map.of("account_id", "A", "menus", List.of("국")), "u"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("한도");
    }

    @Test void ingredientsNamedInMenuGoFirst() {
        var names = MenuRecipeDraftService.promptIngredients(List.of("애호박볶음"),
            List.of(ingredient("A", "감자", "g"), ingredient("B", "애호박", "g")));
        assertThat(names).containsExactly("애호박(g)", "감자(g)");
    }

    @Test void costUsesCachedAndOutputPrices() {
        var client = new OpenAiClient("", "m", "low", new BigDecimal("0.75"), new BigDecimal("0.075"), new BigDecimal("4.50"),
            BigDecimal.ZERO, WebClient.builder(), mock(AiUsageMapper.class));
        // 입력 1500(그중 캐시 500) + 출력 600 → (1000×0.75 + 500×0.075 + 600×4.5) / 1e6
        assertThat(client.cost(1500, 500, 600)).isEqualByComparingTo("0.003488");
    }

    @Test void dailyLimitStopsCallAndKeyIsRequired() {
        var usage = mock(AiUsageMapper.class);
        when(usage.todayCost()).thenReturn(new BigDecimal("5.01"));
        var limited = new OpenAiClient("key", "m", "low", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("5"), WebClient.builder(), usage);
        var caller = new OpenAiClient.Caller("F", "A", "u", 1);
        assertThatThrownBy(() -> limited.respond(caller, "s", "u", null, null)).hasMessageContaining("한도");
        var noKey = new OpenAiClient("", "m", "low", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, WebClient.builder(), usage);
        assertThatThrownBy(() -> noKey.respond(caller, "s", "u", null, null)).hasMessageContaining("키");
        verify(usage, never()).insert(anyMap());
    }
}
