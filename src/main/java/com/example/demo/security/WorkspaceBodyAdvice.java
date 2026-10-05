package com.example.demo.security;

import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@RestControllerAdvice
public class WorkspaceBodyAdvice extends RequestBodyAdviceAdapter {
    private final WorkspaceAccess access;
    private final ObjectMapper json;
    public WorkspaceBodyAdvice(WorkspaceAccess access,ObjectMapper json) { this.access=access; this.json=json; }
    @Override public boolean supports(MethodParameter p,Type target,Class<? extends HttpMessageConverter<?>> converter) {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return false;
        String type=attrs.getRequest().getContentType();
        return WorkspaceAccess.protectedPath(WorkspaceAccess.path(attrs.getRequest())) && type!=null && type.contains("application/json");
    }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter p,Type target,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        var request=((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getRequest();
        var user=WorkspaceAccess.user(request);
        var body=json.readTree(input.getBody());
        access.checkBody(user,body);
        if(body instanceof ObjectNode object) {
            object.put("user_id",user.userId());
            if(!user.headquarters()) object.put("account_id",user.accountId());
            if(object.get("order") instanceof ObjectNode order) order.put("user_id",user.userId());
        }
        byte[] bytes=json.writeValueAsBytes(body);
        return new HttpInputMessage() {
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            public HttpHeaders getHeaders() { HttpHeaders headers=new HttpHeaders();headers.putAll(input.getHeaders());headers.setContentLength(bytes.length);return headers; }
        };
    }
}
