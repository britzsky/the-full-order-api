package com.example.demo.controller;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import com.example.demo.security.WorkspaceAccess;
import com.example.demo.service.WorkspaceNotificationService;
@RestController
@RequestMapping("/v2/workspace/notifications")
public class WorkspaceNotificationController {
    private final WorkspaceNotificationService service;
    private final WorkspaceAccess access;
    public WorkspaceNotificationController(WorkspaceNotificationService service,WorkspaceAccess access) { this.service=service;this.access=access; }
    @PostMapping("/refresh") public Object refresh(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        String account=Objects.toString(body.get("account_id"),"");access.checkAccount(WorkspaceAccess.user(request),account);
        return service.refresh(account);
    }
    @PostMapping("/state") public ResponseEntity<?> state(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        var user=WorkspaceAccess.user(request);String account=Objects.toString(body.get("account_id"),"");access.checkAccount(user,account);
        try { service.state(account,Objects.toString(body.get("event_key"),""),Objects.toString(body.get("fingerprint"),""),Objects.toString(body.get("status"),""),user.userId());return ResponseEntity.ok(Map.of("ok",true)); }
        catch(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); }
    }
}
