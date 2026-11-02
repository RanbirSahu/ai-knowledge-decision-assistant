package com.example.assistant.web;

import com.example.assistant.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Guards every /api/admin/** route: only a valid admin session ("adm_..." token) gets through. Assistant tokens never do. */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
    private final AuthService auth;

    public AdminAuthInterceptor(AuthService auth) { this.auth = auth; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        OrgPrincipal org = auth.requireOrg(request.getHeader("Authorization"));   // throws ApiException(401) otherwise
        request.setAttribute("org", org);
        return true;
    }
}
