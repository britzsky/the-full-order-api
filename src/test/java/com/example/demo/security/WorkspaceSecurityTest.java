package com.example.demo.security;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkspaceSecurityTest {
    JdbcTemplate jdbc;
    MockMvc mvc;
    @RestController static class Endpoint {
        @GetMapping({"/v2/inventory/movements", "/v2/supplier-integration/site-mapping"})
        Map<String,Object> read() { return Map.of("ok",true); }
        @PostMapping("/v2/inventory/adjust")
        Map<String,Object> write(@RequestBody Map<String,Object> body) { return body; }
    }
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class);
        var access=new WorkspaceAccess(jdbc);
        mvc=MockMvcBuilders.standaloneSetup(new Endpoint())
            .addInterceptors(new WorkspaceSecurityConfig(access).interceptor())
            .setControllerAdvice(new WorkspaceBodyAdvice(access,new ObjectMapper())).build();
    }
    MockHttpSession session(String role) {
        var session=new MockHttpSession();
        session.setAttribute(WorkspaceUser.SESSION_KEY,new WorkspaceUser("worker",""+role,"A"));
        return session;
    }
    @Test void requiresSession() throws Exception {
        mvc.perform(get("/v2/inventory/movements").param("account_id","A")).andExpect(status().isUnauthorized());
    }
    @Test void siteMustUseOwnScope() throws Exception {
        mvc.perform(get("/v2/inventory/movements").session(session("3"))).andExpect(status().isForbidden());
        mvc.perform(get("/v2/inventory/movements").session(session("3")).param("account_id","B")).andExpect(status().isForbidden());
        mvc.perform(get("/v2/inventory/movements").session(session("3")).param("account_id","A")).andExpect(status().isOk());
    }
    @Test void headquartersCanReadOtherAccountAndMapping() throws Exception {
        mvc.perform(get("/v2/inventory/movements").session(session("2")).param("account_id","B")).andExpect(status().isOk());
        mvc.perform(get("/v2/supplier-integration/site-mapping").session(session("2"))).andExpect(status().isOk());
        mvc.perform(get("/v2/supplier-integration/site-mapping").session(session("3")).param("account_id","A")).andExpect(status().isForbidden());
    }
    @Test void mutationRequiresRequestHeader() throws Exception {
        mvc.perform(post("/v2/inventory/adjust").session(session("3")).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void overwritesActorAndInjectsSiteScope() throws Exception {
        mvc.perform(post("/v2/inventory/adjust").session(session("3")).header("X-Workspace-Request","1")
            .contentType("application/json").content("{\"user_id\":\"forged\",\"order\":{\"user_id\":\"forged\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user_id").value("worker"))
            .andExpect(jsonPath("$.account_id").value("A")).andExpect(jsonPath("$.order.user_id").value("worker"));
    }
    @Test void rejectsForeignNestedAccount() throws Exception {
        mvc.perform(post("/v2/inventory/adjust").session(session("3")).header("X-Workspace-Request","1")
            .contentType("application/json").content("{\"items\":[{\"account_id\":\"B\"}]}"))
            .andExpect(status().isForbidden());
    }
    @Test void rejectsForeignResourceEvenWhenAccountMatches() throws Exception {
        when(jdbc.queryForList(anyString(),eq(String.class),eq("foreign-balance"))).thenReturn(List.of("B"));
        mvc.perform(post("/v2/inventory/adjust").session(session("3")).header("X-Workspace-Request","1")
            .contentType("application/json").content("{\"account_id\":\"A\",\"inventory_balance_id\":\"foreign-balance\"}"))
            .andExpect(status().isForbidden());
    }
}
