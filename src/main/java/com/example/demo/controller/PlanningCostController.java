package com.example.demo.controller;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.example.demo.service.PlanningCostService;

/** 거래처 식재료 편성 단가(자동: 입고 단가 → 본사 표준 단가, 직접 입력 시 그 값). 공급사 상품과 무관. */
@RestController
@RequestMapping("/v2/menu-management/planning-cost")
public class PlanningCostController {
    private final PlanningCostService service;
    public PlanningCostController(PlanningCostService service){this.service=service;}
    @GetMapping public ResponseEntity<?> preview(@RequestParam String account_id,@RequestParam String ingredient_id) {
        try{return ResponseEntity.ok(service.preview(account_id,ingredient_id));}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    }
    @PostMapping public ResponseEntity<?> confirm(@RequestBody Map<String,Object> body) {
        try{return ResponseEntity.ok(service.confirm(body));}
        catch(IllegalArgumentException|ArithmeticException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()==null?"기준단가 입력값을 확인해주세요.":e.getMessage()));}
    }
}
