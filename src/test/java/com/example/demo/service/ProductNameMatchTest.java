package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** 식재료명 ↔ 공급상품명 유사도: 띄어쓰기·괄호 무시, 동의어, 한 글자 식재료 오탐 방지 */
class ProductNameMatchTest {
    @Test void containsSynonymPartialAndSingleCharacterRules() {
        assertThat(ProductNameMatch.score("양파","양파(국산) 1kg/망").value()).isEqualTo(30);
        assertThat(ProductNameMatch.score("배추 김치","배추김치 10kg").value()).isEqualTo(30);
        assertThat(ProductNameMatch.score("돼지고기","돈육 앞다리(국내산) 1kg").value()).isEqualTo(25);
        assertThat(ProductNameMatch.score("소고기","한우 불고기용 1kg").reason()).contains("한우");
        assertThat(ProductNameMatch.score("닭고기","닭 정육 2kg").value()).as("한 글자 동의어는 단어 일치").isEqualTo(25);
        assertThat(ProductNameMatch.score("닭고기","닭가슴살 1kg").value()).isZero();
        assertThat(ProductNameMatch.score("무","무 1개").value()).isEqualTo(30);
        assertThat(ProductNameMatch.score("무","무농약 양파").value()).as("한 글자 식재료는 단어가 같을 때만").isZero();
        assertThat(ProductNameMatch.score("청양 고추","청양고추 1kg").value()).isEqualTo(30);
        assertThat(ProductNameMatch.score("냉동 새우살","새우 칵테일").value()).isZero();
        assertThat(ProductNameMatch.score("새우살 냉동","새우살 500g").value()).isEqualTo(10);
    }
}
