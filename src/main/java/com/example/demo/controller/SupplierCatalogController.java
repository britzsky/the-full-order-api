package com.example.demo.controller;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.SupplierCatalogService;

@RestController
@RequestMapping("/v2/catalog")
public class SupplierCatalogController {
    private final SupplierCatalogService service;

    public SupplierCatalogController(SupplierCatalogService service) { this.service = service; }

    @GetMapping("/suppliers") public ResponseEntity<?> suppliers(@RequestParam Map<String, Object> p) { return ok(() -> service.suppliers(p)); }
    @PostMapping("/suppliers") public ResponseEntity<?> createSupplier(@RequestBody Map<String, Object> b) { return ok(() -> service.createSupplier(b)); }
    @PatchMapping("/suppliers") public ResponseEntity<?> updateSupplier(@RequestBody Map<String, Object> b) { return ok(() -> service.updateSupplier(b)); }
    @DeleteMapping("/suppliers") public ResponseEntity<?> deleteSupplier(@RequestBody Map<String, Object> b) { return ok(() -> service.deleteSupplier(b)); }

    @GetMapping("/products") public ResponseEntity<?> products(@RequestParam Map<String, Object> p) { return ok(() -> service.products(p)); }
    @PostMapping("/products") public ResponseEntity<?> createProduct(@RequestBody Map<String, Object> b) { return ok(() -> service.createProduct(b)); }
    @PatchMapping("/products") public ResponseEntity<?> updateProduct(@RequestBody Map<String, Object> b) { return ok(() -> service.updateProduct(b)); }
    @DeleteMapping("/products") public ResponseEntity<?> deleteProduct(@RequestBody Map<String, Object> b) { return ok(() -> service.deleteProduct(b)); }

    @GetMapping("/prices") public ResponseEntity<?> prices(@RequestParam Map<String, Object> p) { return ok(() -> service.prices(p)); }
    @PostMapping("/prices") public ResponseEntity<?> createPrice(@RequestBody Map<String, Object> b) { return ok(() -> service.createPrice(b)); }
    @PatchMapping("/prices") public ResponseEntity<?> updatePrice(@RequestBody Map<String, Object> b) { return ok(() -> service.updatePrice(b)); }
    @DeleteMapping("/prices") public ResponseEntity<?> deletePrice(@RequestBody Map<String, Object> b) { return ok(() -> service.deletePrice(b)); }

    @GetMapping("/account-products") public ResponseEntity<?> accountProducts(@RequestParam Map<String, Object> p) { return ok(() -> service.accountProducts(p)); }
    @PostMapping("/account-products") public ResponseEntity<?> createAccountProduct(@RequestBody Map<String, Object> b) { return ok(() -> service.createAccountProduct(b)); }
    @PatchMapping("/account-products") public ResponseEntity<?> updateAccountProduct(@RequestBody Map<String, Object> b) { return ok(() -> service.updateAccountProduct(b)); }
    @DeleteMapping("/account-products") public ResponseEntity<?> deleteAccountProduct(@RequestBody Map<String, Object> b) { return ok(() -> service.deleteAccountProduct(b)); }

    private ResponseEntity<?> ok(Supplier<?> action) {
        try { return ResponseEntity.ok(action.get()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage())); }
    }
}
