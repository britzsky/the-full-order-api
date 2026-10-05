package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.WebClient;
import com.example.demo.controller.OurhomeIntegrationController;
import com.fasterxml.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "RUN_OURHOME_READONLY_TEST", matches = "true")
class OurhomeSiteLiveTest {
    @Test void readsDevelopmentSitesThroughControllerWithoutDatabase() throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application-secret.properties"))) {
            p.load(reader);
        }
        assertEquals("https://devtosapi.ourhome.co.kr", p.getProperty("ourhome.api.base-url"));
        ObjectMapper json = new ObjectMapper();
        var service = new OurhomeSiteService(WebClient.builder(), json, p.getProperty("ourhome.api.base-url"),
                p.getProperty("ourhome.api.service-id"), p.getProperty("ourhome.api.client-token"),
                p.getProperty("ourhome.api.encrypt-seed"), p.getProperty("ourhome.api.tax-no"),
                p.getProperty("ourhome.api.head-group-code"));
        String body = MockMvcBuilders.standaloneSetup(new OurhomeIntegrationController(service)).build()
                .perform(get("/v2/supplier-integration/ourhome/sites")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var result = json.readTree(body);
        assertTrue(result.path("sites").isArray());
        assertFalse(body.contains(p.getProperty("ourhome.api.client-token")));
        assertFalse(body.contains("tokenInfo"));
        Files.createDirectories(Path.of(".tmp"));
        Files.writeString(Path.of(".tmp/ourhome-ui-response.json"), body, StandardCharsets.UTF_8);
        var site = service.sites().sites().stream().filter(s -> s.siteCode().equals("FU0UP")).findFirst().orElseThrow();
        assertFalse(site.deliveryDates().isEmpty());
        String date = site.deliveryDates().get(0);
        var products = service.products(site.siteCode(), date, "", "A", "");
        Files.writeString(Path.of(".tmp/ourhome-products-ui.json"), json.writeValueAsString(products), StandardCharsets.UTF_8);
        if (products.hasNext()) {
            var next = service.products(site.siteCode(), date, "", "A", products.nextKey());
            Files.writeString(Path.of(".tmp/ourhome-products-next-ui.json"), json.writeValueAsString(next), StandardCharsets.UTF_8);
        }
        var orders = service.orders(site.siteCode(), date);
        Files.writeString(Path.of(".tmp/ourhome-orders-ui.json"), json.writeValueAsString(orders), StandardCharsets.UTF_8);
    }
}
