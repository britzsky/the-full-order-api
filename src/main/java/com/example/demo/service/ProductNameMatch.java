package com.example.demo.service;

import java.util.*;

/**
 * 식재료명과 공급상품명의 유사도. 공급사마다 상품명이 달라(돼지고기 ↔ 돈육) 띄어쓰기·괄호를 무시하고 동의어를 같은 말로 본다.
 * 추천 정렬용이며 이것만으로 자동 연결하지 않는다. 프런트 src/utils/productMatch.ts와 같은 동의어 표를 쓴다(변경 시 함께 수정).
 */
public final class ProductNameMatch {
    private ProductNameMatch() {}

    /** 같은 묶음의 말은 같은 식재료로 본다. 한 글자 일반어(소·파 등)는 오탐이 많아 넣지 않는다. */
    static final List<List<String>> SYNONYMS = List.of(
        List.of("돼지고기", "돈육", "돼지"), List.of("소고기", "쇠고기", "우육", "한우"), List.of("닭고기", "계육", "닭"),
        List.of("계란", "달걀", "난류"), List.of("쌀", "백미", "멥쌀"), List.of("고춧가루", "고추가루"),
        List.of("식용유", "대두유", "콩기름"), List.of("설탕", "백설탕", "정백당"), List.of("소금", "천일염", "정제염"),
        List.of("간장", "진간장", "양조간장"), List.of("참치캔", "참치"), List.of("어묵", "오뎅"),
        List.of("요구르트", "요거트"), List.of("마늘", "깐마늘", "다진마늘"), List.of("명태", "동태", "황태"),
        List.of("김치", "배추김치"), List.of("소시지", "소세지"), List.of("밀가루", "소맥분"),
        List.of("전분", "감자전분", "옥수수전분"), List.of("국수면", "소면", "중면"), List.of("떡", "떡볶이떡", "가래떡"));

    public record Score(int value, String reason) {}

    static String normalize(Object value) {
        return value == null ? "" : value.toString().toLowerCase(Locale.ROOT).replaceAll("[\\s()\\[\\]{}<>,./·_\\-+*&]", "");
    }

    static List<String> tokens(Object value) {
        List<String> tokens = new ArrayList<>();
        for (String token : (value == null ? "" : value.toString().toLowerCase(Locale.ROOT)).split("[\\s()\\[\\]{}<>,./·_\\-+*&]+"))
            if (!token.isBlank()) tokens.add(token);
        return tokens;
    }

    static List<String> aliases(String ingredient) {
        String key = normalize(ingredient);
        for (List<String> group : SYNONYMS)
            if (group.stream().anyMatch(word -> normalize(word).equals(key)))
                return group.stream().map(ProductNameMatch::normalize).filter(word -> !word.equals(key)).toList();
        return List.of();
    }

    /** 식재료 쪽 미리 계산(한 번만): 정규화 이름, 동의어, 2글자 이상 단어 */
    public record Query(String label, String key, List<String> aliases, List<String> tokens) {}
    /** 상품 쪽 미리 계산(상품마다 한 번만): 정규화 이름, 정규화 단어 집합 */
    public record Product(String normalized, Set<String> tokens) {}

    public static Query query(String ingredientName) {
        String label = ingredientName == null ? "" : ingredientName.trim();
        List<String> words = tokens(label).stream().filter(token -> token.length() >= 2).map(ProductNameMatch::normalize).toList();
        return new Query(label, normalize(label), aliases(label), words);
    }

    public static Product product(String productName) {
        Set<String> words = new HashSet<>();
        for (String token : tokens(productName)) words.add(normalize(token));
        return new Product(normalize(productName), words);
    }

    /** 0이면 이름 근거 없음. 30 이름 포함, 25 동의어 포함, 10 단어 일부 일치. 한 글자 식재료는 단어가 정확히 같을 때만. */
    public static Score score(String ingredientName, String productName) {
        return score(query(ingredientName), product(productName));
    }

    /**
     * 화면 검색 점수(프런트 productSearchScore와 같은 규칙). 0이면 제외. 상품명 유사도가 없으면 상품코드·공급사명 포함 5.
     * 여러 단어 검색은 단어마다 이름·코드 중 하나에 맞아야 하며 가장 낮은 점수를 쓴다.
     */
    public static int searchScore(String keyword, String productName, String itemCode, String supplierName) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) return 1;
        Product product = product(productName);
        int whole = score(query(trimmed), product).value();
        if (whole > 0) return whole;
        String extra = normalize(itemCode + " " + supplierName);
        List<String> words = tokens(trimmed);
        if (words.isEmpty()) return 0;
        int min = Integer.MAX_VALUE;
        for (String word : words) {
            int value = Math.max(score(query(word), product).value(), extra.contains(normalize(word)) ? 5 : 0);
            if (value == 0) return 0;
            min = Math.min(min, value);
        }
        return min;
    }

    /** 대량 비교용(일괄 추천): 식재료·상품을 미리 계산해 두고 비교만 한다(정규식 반복 없음). */
    public static Score score(Query ingredient, Product product) {
        if (ingredient.key().isEmpty() || product.normalized().isEmpty()) return new Score(0, "");
        if (ingredient.key().length() == 1)
            return product.tokens().contains(ingredient.key()) ? new Score(30, "상품명 일치") : new Score(0, "");
        if (product.normalized().contains(ingredient.key())) return new Score(30, "상품명에 '" + ingredient.label() + "' 포함");
        for (String alias : ingredient.aliases())
            if (alias.length() > 1 ? product.normalized().contains(alias) : product.tokens().contains(alias))
                return new Score(25, "같은 말 '" + alias + "' 포함");
        for (String token : ingredient.tokens())
            if (product.normalized().contains(token)) return new Score(10, "이름 일부 일치");
        return new Score(0, "");
    }
}
