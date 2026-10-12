package com.example.demo.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.IngredientLinkService;
import com.example.demo.service.IngredientLinkSuggestionService;

@RestController
@RequestMapping("/v2/catalog/ingredient-links")
public class IngredientLinkController {
    private final IngredientLinkService service;
    private final IngredientLinkSuggestionService suggestions;
    public IngredientLinkController(IngredientLinkService service, IngredientLinkSuggestionService suggestions) { this.service=service; this.suggestions=suggestions; }

    /*
     * part : 거래처 식자재 연결 method : suggestions comment : 식자재에 연결할 공급상품 추천(최대 5개). 다른 거래처의 같은 식자재 연결·이 거래처 발주 이력·상품명/동의어 유사도 점수와 근거. 기준단위 환산 불가 상품 제외. 저장은 change 사용
     */
    /*
     * part : 거래처 식자재 연결 method : unlinkedSuggestions comment : 메뉴에 쓰이지만 연결 상품이 없는 식자재 전체와 식자재별 추천(최대 3개). 일괄 연결 화면용, 저장은 change를 품목마다 사용
     */
    @GetMapping("/suggestions/unlinked")
    public ResponseEntity<?> unlinkedSuggestions(@RequestParam Map<String,Object> params) {
        try { return ResponseEntity.ok(suggestions.unlinked(params)); }
        catch(IllegalArgumentException | java.time.format.DateTimeParseException e) { return ResponseEntity.badRequest().body(Map.of("code",400,"message",e.getMessage())); }
    }

    @GetMapping("/suggestions")
    public ResponseEntity<?> suggestions(@RequestParam Map<String,Object> params) {
        try { return ResponseEntity.ok(suggestions.suggestions(params)); }
        catch(IllegalArgumentException | java.time.format.DateTimeParseException e) { return ResponseEntity.badRequest().body(Map.of("code",400,"message",e.getMessage())); }
    }

    /*
     * part : 거래처 식자재 연결 method : change comment : 거래처 식자재의 공급상품 연결/해제/교체(LINK·UNLINK·REPLACE). 공통 상품의 식자재 분류는 바꾸지 않고 해당 거래처 매핑만 변경
     */
    @PostMapping("/change")
    public ResponseEntity<?> change(@RequestBody Map<String,Object> body) {
        try { return ResponseEntity.ok(service.change(body)); }
        catch(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("code",400,"message",e.getMessage())); }
    }
}
