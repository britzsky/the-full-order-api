package com.example.demo.security;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import com.example.demo.WebConfig;
import com.example.demo.controller.LoginController;
import com.example.demo.service.LoginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=WorkspaceLoginHttpTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"file.upload-dir=./.tmp","server.servlet.session.cookie.http-only=true"})
class WorkspaceLoginHttpTest {
    @Configuration
    @EnableAutoConfiguration(exclude=DataSourceAutoConfiguration.class)
    @Import({LoginController.class,WorkspaceAccess.class,WorkspaceSecurityConfig.class,WorkspaceBodyAdvice.class,WorkspaceFormFilter.class,Endpoint.class})
    static class Config {
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean WebConfig webConfig() { return mock(WebConfig.class); }
        @Bean LoginService loginService() {
            var service=mock(LoginService.class);
            when(service.Login(any())).thenAnswer(call -> {
                Map<?,?> input=call.getArgument(0);
                if(!"correct".equals(input.get("password"))) return Map.of("status_code","400");
                return Map.of("status_code","200","user_id","worker","user_type","3","position",8,"department","test","account_id","A","user_name","시험","account_name","시험 거래처");
            });return service;
        }
    }
    @RestController static class Endpoint {
        @PostMapping("/Inventory/TestForm") Map<String,Object> form(@RequestParam Map<String,Object> p) { return p; }
        @GetMapping("/v2/inventory/test") Map<String,Object> read() { return Map.of("ok",true); }
    }
    @LocalServerPort int port;
    HttpResponse<String> request(HttpClient client,String path,String body,String type) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(body==null) b.GET(); else b.header("Content-Type",type).header("X-Workspace-Request","1").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void realHttpCookieLoginScopeFormActorFailureAndLogout() throws Exception {
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);var client=HttpClient.newBuilder().cookieHandler(cookies).build();
        assertThat(request(client,"/User/Session",null,"").statusCode()).isEqualTo(401);
        var login=request(client,"/User/Login","{\"password\":\"correct\",\"user_type\":\"2\",\"account_id\":\"B\"}","application/json");
        assertThat(login.statusCode()).isEqualTo(200);assertThat(login.headers().firstValue("set-cookie").orElse("")).contains("HttpOnly");
        assertThat(request(client,"/User/Session",null,"").body()).contains("worker","\"userType\":\"3\"");
        assertThat(request(client,"/v2/inventory/test?account_id=A",null,"").statusCode()).isEqualTo(200);
        assertThat(request(client,"/v2/inventory/test?account_id=B",null,"").statusCode()).isEqualTo(403);
        var form=request(client,"/Inventory/TestForm","account_id=A&user_id=forged","application/x-www-form-urlencoded");
        assertThat(new ObjectMapper().readTree(form.body()).path("user_id").asText()).isEqualTo("worker");
        request(client,"/User/Login","{\"password\":\"wrong\"}","application/json");
        assertThat(request(client,"/User/Session",null,"").statusCode()).isEqualTo(401);
        request(client,"/User/Login","{\"password\":\"correct\"}","application/json");
        request(client,"/User/Logout","{}","application/json");
        assertThat(request(client,"/User/Session",null,"").statusCode()).isEqualTo(401);
    }
}
