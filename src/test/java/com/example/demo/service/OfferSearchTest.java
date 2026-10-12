package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** 상품 목록 서버 검색: SQL 패턴(동의어·이스케이프)과 화면과 같은 검색 점수 */
class OfferSearchTest {
    @Test void patternsIncludeSynonymsAndEscapeLikeWildcards() {
        assertThat(SupplierIntegrationService.namePatterns("돼지고기")).contains("돼지고기","돈육","돼지");
        assertThat(SupplierIntegrationService.namePatterns("청양 고추")).contains("청양고추","청양","고추");
        // '_'는 정규화에서 구분자로 빠지고, '%'는 LIKE 와일드카드가 되지 않게 이스케이프된다
        assertThat(SupplierIntegrationService.namePatterns("100%원액")).allMatch(p->p.contains("\\%"));
    }
    @Test void searchScoreMatchesFrontendRules() {
        assertThat(ProductNameMatch.searchScore("돼지고기","돈육 앞다리(국내산) 1kg","C1","아워홈")).isEqualTo(25);
        assertThat(ProductNameMatch.searchScore("삼겹","한돈 삼겹살","C2","아워홈")).isEqualTo(30);
        assertThat(ProductNameMatch.searchScore("C2","양파 1kg","C2","아워홈")).isEqualTo(5);
        assertThat(ProductNameMatch.searchScore("양파 아워홈","양파 1kg","C1","아워홈")).as("한 단어가 상품명에 있으면 이름 일부 일치(프런트와 동일)").isEqualTo(10);
        assertThat(ProductNameMatch.searchScore("C1 아워홈","양파 1kg","C1","아워홈")).as("이름에 없으면 단어마다 코드·공급사 중 하나").isEqualTo(5);
        assertThat(ProductNameMatch.searchScore("C9 아워홈","양파 1kg","C1","아워홈")).isZero();
        assertThat(ProductNameMatch.searchScore("무","무농약 양파","C1","아워홈")).isZero();
    }
}
