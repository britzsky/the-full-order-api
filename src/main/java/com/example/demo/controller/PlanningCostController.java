package com.example.demo.controller;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.example.demo.service.PlanningCostService;
@RestController
@RequestMapping("/v2/catalog/ingredient-links/planning-cost")
public class PlanningCostController {
    private final PlanningCostService service;
    public PlanningCostController(PlanningCostService service){this.service=service;}
    @GetMapping public ResponseEntity<?> preview(@RequestParam String account_id,@RequestParam String ingredient_id,@RequestParam long supplier_product_id) {
        try{return ResponseEntity.ok(service.preview(account_id,ingredient_id,supplier_product_id));}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    }
    @PostMapping public ResponseEntity<?> confirm(@RequestBody Map<String,Object> body) {
        try{return ResponseEntity.ok(service.confirm(body));}
        catch(IllegalArgumentException|ArithmeticException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()==null?"기준단가 입력값을 확인해주세요.":e.getMessage()));}
    }
}
