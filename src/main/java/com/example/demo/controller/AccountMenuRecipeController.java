package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.AccountMenuRecipeService;

@RestController @RequestMapping("/v2/menu-management")
public class AccountMenuRecipeController {
    private final AccountMenuRecipeService service;
    public AccountMenuRecipeController(AccountMenuRecipeService service){this.service=service;}
    @GetMapping("/ingredients") public ResponseEntity<?> ingredients(@RequestParam Map<String,Object>p){return run(()->service.ingredients(p));}
    @PostMapping("/ingredients") public ResponseEntity<?> saveIngredient(@RequestBody Map<String,Object>b){return run(()->service.saveIngredient(b));}
    @DeleteMapping("/ingredients") public ResponseEntity<?> deleteIngredient(@RequestBody Map<String,Object>b){return run(()->service.deleteIngredient(b));}
    @GetMapping("/menus") public ResponseEntity<?> menus(@RequestParam Map<String,Object>p){return run(()->service.menus(p));}
    @PostMapping("/menus") public ResponseEntity<?> saveMenu(@RequestBody Map<String,Object>b){return run(()->service.saveMenu(b));}
    @DeleteMapping("/menus") public ResponseEntity<?> deleteMenu(@RequestBody Map<String,Object>b){return run(()->service.deleteMenu(b));}
    @PostMapping("/menus/register-with-recipe") public ResponseEntity<?> register(@RequestBody Map<String,Object>b){return run(()->service.registerMenu(b));}
    @GetMapping("/recipes") public ResponseEntity<?> recipes(@RequestParam Map<String,Object>p){return run(()->service.recipes(p));}
    @PostMapping("/recipes") public ResponseEntity<?> createRecipe(@RequestBody Map<String,Object>b){return run(()->service.createRecipe(b));}
    @PatchMapping("/recipes") public ResponseEntity<?> updateRecipe(@RequestBody Map<String,Object>b){return run(()->service.updateRecipe(b));}
    @DeleteMapping("/recipes") public ResponseEntity<?> deleteRecipe(@RequestBody Map<String,Object>b){return run(()->service.deleteRecipe(b));}
    private ResponseEntity<?> run(Supplier<?>a){try{return ResponseEntity.ok(a.get());}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("code",400,"message",e.getMessage()));}}
}
