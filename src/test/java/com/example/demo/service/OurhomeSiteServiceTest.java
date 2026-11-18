package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

class OurhomeSiteServiceTest {
    private OurhomeSiteService service(HttpStatus status, String body) {
        return new OurhomeSiteService(WebClient.builder().exchangeFunction(request -> {
            assertEquals("https://devtosapi.ourhome.co.kr/api/chaeum/v3/getBusiInfo", request.url().toString());
            return Mono.just(ClientResponse.create(status).header("Content-Type", "application/json").body(body).build());
        }), new ObjectMapper(), "https://devtosapi.ourhome.co.kr", "2007", "test-secret", "10", "1234567890", "TEST");
    }

    @Test void computesDocumentFormulaIncludingLeadingZeroFactor() {
        assertEquals("159202", OurhomeSiteService.validCode("20260930091944", "2007", "10"));
        assertEquals("3405", OurhomeSiteService.validCode("20260930120000", "2007", "768"));
    }

    @Test void mapsKoreanBusinessFieldsAndNullDeliveryDatesWithoutCredentials() throws Exception {
        var result = service(HttpStatus.OK, """
            {"rtnCd":0,"tokenInfo":{"clientToken":"test-secret"},"result":{"data":[
             {"busiplCd":"A","busiplNm":"늘사랑","depositDt":"20261001,20261002","clientToken":"test-secret"},
             {"busiplCd":"B","depositDt":null}]}}
            """).sites();
        assertEquals(2, result.sites().size());
        assertEquals("늘사랑", result.sites().get(0).siteName());
        assertEquals("2026-10-01", result.sites().get(0).deliveryDates().get(0));
        assertTrue(result.sites().get(1).deliveryDates().isEmpty());
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("test-secret"));
    }

    @Test void rejectsHttpBusinessAndMalformedResponsesWithoutEchoingSecrets() {
        for (var status : new HttpStatus[] {HttpStatus.OK, HttpStatus.BAD_REQUEST}) {
            var error = assertThrows(IllegalStateException.class,
                    () -> service(status, "{\"rtnCd\":-810,\"rtnMsg\":\"test-secret\"}").sites());
            assertFalse(error.getMessage().contains("test-secret"));
            assertNull(error.getCause());
        }
        for (String body : new String[] {"{}", "not-json", "{\"rtnCd\":0,\"result\":{\"data\":null}}"}) {
            assertThrows(IllegalStateException.class, () -> service(HttpStatus.OK, body).sites());
        }
    }

    @Test void surfacesProviderBusinessMessageFromHttp400() {
        var error = assertThrows(IllegalStateException.class, () -> service(HttpStatus.BAD_REQUEST,
                "{\"rtnCd\":-2,\"rtnMsg\":\"해당 입고일자에 적용되는 집계 데이터가 없습니다.\",\"result\":null}").sites());
        assertEquals("아워홈 조회 결과: 해당 입고일자에 적용되는 집계 데이터가 없습니다.", error.getMessage());
        assertThrows(IllegalStateException.class, () -> service(HttpStatus.INTERNAL_SERVER_ERROR, "{}").sites());
    }

    /** 사업자번호 둘(법인·개인): 번호마다 사업장 목록을 받아 합친다(같은 사업장은 한 번). 한쪽만 실패하면 나머지로 계속. */
    private OurhomeSiteService twoTaxNos(String... bodies) {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        return new OurhomeSiteService(WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse
                .create(HttpStatus.OK).header("Content-Type", "application/json").body(bodies[calls.getAndIncrement()])
                .build())), new ObjectMapper(), "https://devtosapi.ourhome.co.kr", "2007", "test-secret", "10",
                "1234567890, 9876543210", "TEST");
    }

    @Test void mergesSitesOfEveryBusinessNumber() {
        var result = twoTaxNos(
                "{\"rtnCd\":0,\"result\":{\"data\":[{\"busiplCd\":\"CORP\"},{\"busiplCd\":\"SHARED\"}]}}",
                "{\"rtnCd\":0,\"result\":{\"data\":[{\"busiplCd\":\"PERSONAL\"},{\"busiplCd\":\"SHARED\"}]}}").sites();
        assertEquals(java.util.List.of("CORP", "SHARED", "PERSONAL"),
                result.sites().stream().map(OurhomeSiteService.Site::siteCode).toList());

        var partial = twoTaxNos("{\"rtnCd\":-810,\"rtnMsg\":\"권한 없음\"}",
                "{\"rtnCd\":0,\"result\":{\"data\":[{\"busiplCd\":\"PERSONAL\"}]}}").sites();
        assertEquals("PERSONAL", partial.sites().get(0).siteCode());

        assertThrows(IllegalStateException.class, () -> twoTaxNos("{\"rtnCd\":-810}", "{\"rtnCd\":-810}").sites());
        assertEquals(java.util.List.of("1234567890", "9876543210"), OurhomeSiteService.taxNos("1234567890,9876543210"));
    }

    @Test void rejectsInvalidDatesAndSupportsEmptyList() {
        assertThrows(IllegalStateException.class, () -> service(HttpStatus.OK,
                "{\"rtnCd\":0,\"result\":{\"data\":[{\"busiplCd\":\"A\",\"depositDt\":\"20260230\"}]}}").sites());
        assertTrue(service(HttpStatus.OK, "{\"rtnCd\":0,\"result\":{\"data\":[]}}").sites().sites().isEmpty());
    }
}
