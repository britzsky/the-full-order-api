package com.example.demo;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.service.IngredientLinkSuggestionService;

/** 상품 연결 추천 SQL을 실제 로컬 DB에서 실행(읽기 전용, 테스트 트랜잭션 롤백). 공급사 호출 없음. */
@EnabledIfEnvironmentVariable(named="RUN_LINK_SUGGESTION_DB",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalLinkSuggestionReadOnlyTest {
    @Autowired IngredientLinkSuggestionService service;
    @Test void printsSuggestions() {
        for(String ingredient:new String[]{"TIG010","TIG004","TIG001"})
            System.out.println("LINK_SUGGEST "+ingredient+" "+service.suggestions(new HashMap<>(Map.of("account_id","20250819193455","ingredient_id",ingredient))));
        System.out.println("LINK_UNLINKED "+service.unlinked(new HashMap<>(Map.of("account_id","20250819193455"))));
    }
}
