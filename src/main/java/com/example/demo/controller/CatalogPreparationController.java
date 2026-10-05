package com.example.demo.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import com.example.demo.security.WorkspaceAccess;
import com.example.demo.service.CatalogPreparationService;

@RestController
@RequestMapping("/v2/supplier-integration/preparation")
public class CatalogPreparationController {
    private final CatalogPreparationService service;
    private final WorkspaceAccess access;
    public CatalogPreparationController(CatalogPreparationService service,WorkspaceAccess access) { this.service=service;this.access=access; }
    @PostMapping public ResponseEntity<?> prepare(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        var user=WorkspaceAccess.user(request);String account=String.valueOf(body.getOrDefault("account_id",""));access.checkAccount(user,account);
        try { return ResponseEntity.ok(service.prepare(account,String.valueOf(body.get("delivery_date")),user.userId())); }
        catch(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); }
    }
    @GetMapping public Map<String,Object> status(@RequestParam("account_id") String account,@RequestParam("delivery_date") String date,HttpServletRequest request) {
        access.checkAccount(WorkspaceAccess.user(request),account);return service.status(account,date);
    }
}
