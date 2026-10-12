package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** 웰스토리 게이트웨이는 만료 토큰을 HTTP 500 + EGW00123으로 돌려준다. 이것도 토큰 거부로 보고 재발급한다. */
class WelstoryTokenRejectionTest {
    static WebClientResponseException error(int status,String body) {
        return WebClientResponseException.create(status,"x",HttpHeaders.EMPTY,body.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8);
    }
    @Test void expiredTokenBodyAndUnauthorizedAreTokenRejections() {
        assertThat(WelstoryItemLookupService.isTokenRejected(error(500,"{\"resCd\":\"EGW00123\",\"resMsg\":\"기간이 만료된 token 입니다.\"}"))).isTrue();
        assertThat(WelstoryItemLookupService.isTokenRejected(error(401,""))).isTrue();
        assertThat(WelstoryItemLookupService.isTokenRejected(error(500,"{\"resCd\":\"E9999\",\"resMsg\":\"system error\"}"))).isFalse();
    }
}
