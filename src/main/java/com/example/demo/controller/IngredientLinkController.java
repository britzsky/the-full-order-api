package com.example.demo.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.IngredientLinkService;

@RestController
@RequestMapping("/v2/catalog/ingredient-links")
public class IngredientLinkController {
    private final IngredientLinkService service;
    public IngredientLinkController(IngredientLinkService service) { this.service=service; }

    /*
     * part : 거래처 식자재 연결 method : change comment : 거래처 식자재의 공급상품 연결/해제/교체(LINK·UNLINK·REPLACE). 공통 상품의 식자재 분류는 바꾸지 않고 해당 거래처 매핑만 변경
     */
    @PostMapping("/change")
    public ResponseEntity<?> change(@RequestBody Map<String,Object> body) {
        try { return ResponseEntity.ok(service.change(body)); }
        catch(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("code",400,"message",e.getMessage())); }
    }
}
