package com.example.demo.controller;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.example.demo.service.MealPlanImpactService;
@RestController
public class MealPlanImpactController {
    private final MealPlanImpactService service;
    public MealPlanImpactController(MealPlanImpactService service) { this.service=service; }
    @PostMapping("/v2/meal-plans/impact") public ResponseEntity<?> impact(@RequestBody Map<String,Object> body) {
        try { return ResponseEntity.ok(service.compare(body)); }
        catch(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); }
    }
}
