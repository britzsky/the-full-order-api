package com.example.demo.controller;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import com.example.demo.security.WorkspaceAccess;
import com.example.demo.service.KakaoNotificationService;

/** 거래처별 카카오 알림톡 수신자 관리와 시험 발송. */
@RestController
@RequestMapping("/v2/workspace/kakao-recipients")
public class KakaoRecipientController {
    private final KakaoNotificationService service;
    private final WorkspaceAccess access;
    public KakaoRecipientController(KakaoNotificationService service,WorkspaceAccess access) { this.service=service;this.access=access; }

    /* part : 알림톡 method : list comment : 거래처 수신자 목록과 발송 사용 여부 */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam("account_id") String account,HttpServletRequest request) {
        return run(request,account,user->Map.of("active",service.active(),"recipients",service.recipients(account)));
    }
    /* part : 알림톡 method : save comment : 수신자 등록·수정(번호·이름·알림 종류) */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        String account=Objects.toString(body.get("account_id"),"");
        return run(request,account,user->service.saveRecipient(account,body,user));
    }
    /* part : 알림톡 method : deactivate comment : 수신 중지(발송 이력 보존을 위해 삭제하지 않음) */
    @PostMapping("/deactivate")
    public ResponseEntity<?> deactivate(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        String account=Objects.toString(body.get("account_id"),"");
        return run(request,account,user->service.deactivateRecipient(account,body.get("kakao_recipient_id"),user));
    }
    /* part : 알림톡 method : test comment : 시험 발송 */
    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        String account=Objects.toString(body.get("account_id"),"");
        return run(request,account,user->service.test(account,body.get("kakao_recipient_id"),user));
    }

    private ResponseEntity<?> run(HttpServletRequest request,String account,java.util.function.Function<String,Object> action) {
        var user=WorkspaceAccess.user(request);
        if(account.isBlank()) return ResponseEntity.badRequest().body(Map.of("message","거래처를 선택해주세요."));
        access.checkAccount(user,account);
        try { return ResponseEntity.ok(action.apply(user.userId())); }
        catch(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); }
        catch(org.springframework.dao.DuplicateKeyException e) { return ResponseEntity.badRequest().body(Map.of("message","이미 등록된 휴대폰 번호입니다.")); }
    }
}
