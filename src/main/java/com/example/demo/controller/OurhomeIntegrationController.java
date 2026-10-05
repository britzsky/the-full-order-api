package com.example.demo.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.function.Supplier;
import com.example.demo.service.OurhomeSiteService;

@RestController
@RequestMapping("/v2/supplier-integration/ourhome")
public class OurhomeIntegrationController {
    private final OurhomeSiteService service;

    public OurhomeIntegrationController(OurhomeSiteService service) { this.service = service; }

    /* part : 아워홈 연동 method : sites comment : 서버에 설정된 본부의 사업장과 발주 가능일 실시간 조회 */
    @GetMapping("/sites")
    public ResponseEntity<?> sites() {
        return execute(service::sites);
    }

    /* part : 아워홈 연동 method : products comment : 선택 사업장과 납품일의 상품 판가 페이지 조회 */
    @GetMapping("/products")
    public ResponseEntity<?> products(@RequestParam(name = "siteCode") String siteCode,
            @RequestParam(name = "deliveryDate") String deliveryDate,
            @RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "searchType", defaultValue = "A") String searchType,
            @RequestParam(name = "nextKey", defaultValue = "") String nextKey) {
        return execute(() -> service.products(siteCode, deliveryDate, keyword, searchType, nextKey));
    }

    /* part : 아워홈 연동 method : orders comment : 사업장별 주문 입고 내역 조회 (재고 반영 없음) */
    @GetMapping("/orders")
    public ResponseEntity<?> orders(@RequestParam(name = "siteCode") String siteCode,
            @RequestParam(name = "deliveryDate") String deliveryDate) {
        return execute(() -> service.orders(siteCode, deliveryDate));
    }

    private ResponseEntity<?> execute(Supplier<?> action) {
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(action.get());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(502).body(Map.of("message", exception.getMessage()));
        }
    }
}
