package com.example.demo.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.example.demo.service.OurhomeSiteService;

class OurhomeIntegrationControllerTest {
    @Test void bindsProductQueryAndDefaults() throws Exception {
        var service = mock(OurhomeSiteService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OurhomeIntegrationController(service)).build();
        mvc.perform(get("/v2/supplier-integration/ourhome/products")
                .param("siteCode", "FU0UP").param("deliveryDate", "2026-10-01"))
                .andExpect(status().isOk());
        verify(service).products("FU0UP", "2026-10-01", "", "A", "");
        mvc.perform(get("/v2/supplier-integration/ourhome/products")
                .param("siteCode", "FU0UP").param("deliveryDate", "2026-10-02")
                .param("keyword", "대파").param("searchType", "N").param("nextKey", "cursor-2"))
                .andExpect(status().isOk());
        verify(service).products("FU0UP", "2026-10-02", "대파", "N", "cursor-2");
    }

    @Test void bindsOrderQuery() throws Exception {
        var service = mock(OurhomeSiteService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OurhomeIntegrationController(service)).build();
        mvc.perform(get("/v2/supplier-integration/ourhome/orders")
                .param("siteCode", "FU0UP").param("deliveryDate", "2026-09-30"))
                .andExpect(status().isOk());
        verify(service).orders("FU0UP", "2026-09-30");
    }

    @Test void missingRequiredQueryReturns400WithoutCallingProvider() throws Exception {
        var service = mock(OurhomeSiteService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OurhomeIntegrationController(service)).build();
        mvc.perform(get("/v2/supplier-integration/ourhome/products").param("siteCode", "FU0UP"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v2/supplier-integration/ourhome/orders").param("deliveryDate", "2026-10-01"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
