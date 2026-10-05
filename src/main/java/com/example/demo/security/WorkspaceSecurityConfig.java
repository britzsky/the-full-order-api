package com.example.demo.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class WorkspaceSecurityConfig implements WebMvcConfigurer {
    private final WorkspaceAccess access;
    public WorkspaceSecurityConfig(WorkspaceAccess access) { this.access=access; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor());
    }
    HandlerInterceptor interceptor() {
        return new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                String path=WorkspaceAccess.path(request);
                if("OPTIONS".equals(request.getMethod()) || !WorkspaceAccess.protectedPath(path)) return true;
                WorkspaceUser user=WorkspaceAccess.user(request);
                access.authorize(user,path,request.getMethod());
                if(!"GET".equals(request.getMethod()) && !"1".equals(request.getHeader("X-Workspace-Request")))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"업무 요청을 다시 확인해주세요.");
                request.getParameterMap().forEach((key,values) -> {
                    for(String value:values) {
                        if("account_id".equals(key)) access.checkAccount(user,value);
                        access.checkResource(user,key,value);
                    }
                });
                if(!user.headquarters() && "GET".equals(request.getMethod()) && isScoped(path)
                    && !user.accountId().equals(request.getParameter("account_id")))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"소속 거래처를 확인해주세요.");
                return true;
            }
        };
    }
    private static boolean isScoped(String path) {
        return path.startsWith("/v2/inventory") || path.startsWith("/v2/menu-management") || path.startsWith("/v2/meal-plans")
            || path.startsWith("/v2/procurement") || path.startsWith("/v2/discovery") || path.startsWith("/Procurement/")
            || path.startsWith("/Inventory/") || path.startsWith("/Account/") || path.startsWith("/Order/") || path.startsWith("/Menu/Account") || path.startsWith("/Menu/Like")
            || path.startsWith("/Table/") || path.startsWith("/v2/supplier-integration/") || path.equals("/v2/catalog/account-products");
    }
}
