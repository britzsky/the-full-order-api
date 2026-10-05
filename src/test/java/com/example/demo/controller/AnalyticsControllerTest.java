package com.example.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;
import com.example.demo.service.AnalyticsService;

class AnalyticsControllerTest {
    @Test void dashboardAcceptsFiltersAndDefaults() throws Exception {
        var service = mock(AnalyticsService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(service)).build();
        when(service.searchDashboard("branch", 7, 20)).thenReturn(Map.of("summary", Map.of("total_searches", 1)));
        when(service.searchDashboard("", 30, 10)).thenReturn(Map.of("summary", Map.of("total_searches", 0)));
        mvc.perform(get("/Analytics/Dashboard").param("account_id", "branch").param("days", "7").param("limit", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.total_searches").value(1));
        mvc.perform(get("/Analytics/Dashboard")).andExpect(status().isOk());
        verify(service).searchDashboard("", 30, 10);
    }

    @Test void popularSearchesAcceptsFiltersAndRejectsInvalidPeriod() throws Exception {
        var service = mock(AnalyticsService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(service)).build();
        when(service.popularSearches("branch", 90, 8)).thenReturn(List.of(Map.of("query", "onion", "search_count", 1)));
        mvc.perform(get("/Analytics/PopularSearches").param("account_id", "branch").param("days", "90").param("limit", "8"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].query").value("onion"));
        mvc.perform(get("/Analytics/Dashboard").param("days", "invalid")).andExpect(status().isBadRequest());
        verify(service).popularSearches("branch", 90, 8);
    }

    @Test void bindingsDoNotDependOnCompilerParameterMetadata() throws Exception {
        for (String method : List.of("searchDashboard", "popularSearches")) {
            var parameters = AnalyticsController.class.getMethod(method, String.class, int.class, int.class).getParameters();
            assertThat(java.util.Arrays.stream(parameters).map(p -> p.getAnnotation(RequestParam.class).name()))
                    .containsExactly("account_id", "days", "limit");
        }
    }
}
