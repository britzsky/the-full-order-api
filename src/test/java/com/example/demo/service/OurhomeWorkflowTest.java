package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.example.demo.controller.OurhomeIntegrationController;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

class OurhomeWorkflowTest {
    private static final String SITES = """
        {"rtnCd":0,"result":{"data":[{"busiplCd":"SITE","headGrpCd":"HEAD","depositDt":"20261001"}]}}
        """;
    private OurhomeSiteService service(String response, AtomicInteger businessCalls) {
        var builder = WebClient.builder().exchangeFunction(request -> {
            String body = SITES;
            if (!request.url().getPath().endsWith("getBusiInfo")) {
                businessCalls.incrementAndGet();
                body = response;
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build());
        });
        return new OurhomeSiteService(builder, new ObjectMapper(), "https://devtosapi.ourhome.co.kr",
                "2007", "secret", "10", "1234567890", "HEAD");
    }

    @Test void onlyKnownSiteAndAvailableDateMayQueryProducts() {
        var calls = new AtomicInteger();
        var service = service("{}", calls);
        assertThrows(IllegalArgumentException.class, () -> service.products("OTHER", "2026-10-01", "", "A", ""));
        assertThrows(IllegalArgumentException.class, () -> service.products("SITE", "2026-10-02", "", "A", ""));
        assertThrows(IllegalArgumentException.class, () -> service.products("SITE", "2026-02-30", "", "A", ""));
        assertThrows(IllegalArgumentException.class, () -> service.products("SITE", "2026-10-01", "", "BAD", ""));
        assertEquals(0, calls.get());
    }

    @Test void preservesDecimalValuesAndFiltersUnexpectedFields() throws Exception {
        var service = service("""
            {"rtnCd":0,"result":{"totalRow":101,"nextYn":"Y","nextKey":"next-1","data":[
            {"goodcd":"000123","goodnm":"대파","salsUcost":123.45,"odrupYnDesc":"0.5","clientToken":"secret"}]}}
            """, new AtomicInteger());
        var result = service.products("SITE", "2026-10-01", "", "A", "");
        assertEquals("123.45", result.products().get(0).get("salsUcost"));
        assertEquals("000123", result.products().get(0).get("goodcd"));
        assertEquals("0.5", result.products().get(0).get("odrupYnDesc"));
        assertTrue(result.hasNext());
        assertEquals("next-1", result.nextKey());
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("secret"));
        assertThrows(IllegalStateException.class, () -> service.products("SITE", "2026-10-01", "", "A", "next-1"));
    }

    @Test void rejectsMissingOrNonProgressingCursorAndMissingCode() {
        for (String result : new String[] {
                "{\"nextYn\":\"Y\",\"nextKey\":\"\",\"data\":[{\"goodcd\":\"1\"}]}",
                "{\"nextYn\":\"Y\",\"nextKey\":\"more\",\"data\":[]}",
                "{\"nextYn\":\"N\",\"data\":[{}]}"}) {
            var service = service("{\"rtnCd\":0,\"result\":" + result + "}", new AtomicInteger());
            assertThrows(IllegalStateException.class, () -> service.products("SITE", "2026-10-01", "", "A", ""));
        }
    }

    @Test void permitsHistoricalOrderDatesAndReturnsProviderStatesWithoutPosting() {
        var calls = new AtomicInteger();
        var service = service("""
            {"rtnCd":0,"result":{"data":[{"ordKeyVal":"ORDER","clhaqty":"2.5","prcsStatYn":"Y","delYn":"Y"}]}}
            """, calls);
        var result = service.orders("SITE", "2026-09-01");
        assertEquals("2.5", result.orders().get(0).get("clhaqty"));
        assertEquals("Y", result.orders().get(0).get("delYn"));
        assertEquals(1, calls.get());
    }

    @Test void controllerReturnsBadRequestAndEmptySuccessfulPage() throws Exception {
        var service = service("{\"rtnCd\":0,\"result\":{\"nextYn\":\"N\",\"data\":[],\"totalRow\":0}}", new AtomicInteger());
        var mvc = MockMvcBuilders.standaloneSetup(new OurhomeIntegrationController(service)).build();
        mvc.perform(get("/v2/supplier-integration/ourhome/products").param("siteCode", "SITE")
                .param("deliveryDate", "bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/v2/supplier-integration/ourhome/products").param("siteCode", "SITE")
                .param("deliveryDate", "2026-10-01")).andExpect(status().isOk());
    }
}
