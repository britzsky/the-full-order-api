package com.example.demo.security;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Legacy form handlers receive the session actor just like JSON handlers. */
@Component
public class WorkspaceFormFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String content=Objects.toString(request.getContentType(),"");
        if(!WorkspaceAccess.protectedPath(WorkspaceAccess.path(request)) || "GET".equals(request.getMethod())
            || !(content.startsWith("application/x-www-form-urlencoded") || content.startsWith("multipart/form-data"))
            || request.getSession(false)==null || !(request.getSession(false).getAttribute(WorkspaceUser.SESSION_KEY) instanceof WorkspaceUser user)) {
            chain.doFilter(request,response);return;
        }
        // Leave account_id untouched so the interceptor can reject a forged scope.
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private boolean ownScope(String name) { return !user.headquarters() && "account_id".equals(name) && (super.getParameter(name)==null || super.getParameter(name).isBlank()); }
            @Override public String getParameter(String name) { return "user_id".equals(name)?user.userId():ownScope(name)?user.accountId():super.getParameter(name); }
            @Override public String[] getParameterValues(String name) { return "user_id".equals(name)?new String[]{user.userId()}:ownScope(name)?new String[]{user.accountId()}:super.getParameterValues(name); }
            @Override public Map<String,String[]> getParameterMap() { var map=new HashMap<>(super.getParameterMap());map.put("user_id",new String[]{user.userId()});if(ownScope("account_id"))map.put("account_id",new String[]{user.accountId()});return Collections.unmodifiableMap(map); }
            @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(getParameterMap().keySet()); }
        },response);
    }
}
