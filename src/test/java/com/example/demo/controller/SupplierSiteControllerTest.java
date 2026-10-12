package com.example.demo.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.example.demo.service.OurhomeCatalogSyncService;
import com.example.demo.service.SupplierSiteService;
import com.example.demo.service.WelstoryCatalogSyncService;

/** 공급사 서버 오류(웰스토리 500 등)는 우리 서버 500이 아니라 502 + 안내 문구로 돌려준다. */
class SupplierSiteControllerTest {
    final WelstoryCatalogSyncService welstory=mock(WelstoryCatalogSyncService.class);
    final SupplierSiteController controller=new SupplierSiteController(mock(SupplierSiteService.class),welstory,mock(OurhomeCatalogSyncService.class));

    @Test void providerServerErrorBecomesBadGatewayWithMessage() {
        when(welstory.syncSites(anyMap())).thenThrow(WebClientResponseException.create(500,"Internal Server Error",HttpHeaders.EMPTY,new byte[0],null));
        var response=controller.syncSites(new HashMap<>(Map.of("supplier_code","WELSTORY")));
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        var body=(Map<?,?>)response.getBody();
        assertThat(body.get("provider_status")).isEqualTo(500);
        assertThat(body.get("message").toString()).contains("공급사 서버가 오류를 반환했습니다(HTTP 500)").contains("기존 사업장 목록은 그대로");
    }
}
