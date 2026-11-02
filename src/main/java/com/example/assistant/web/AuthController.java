package com.example.assistant.web;

import com.example.assistant.service.AuthService;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    public record RegisterRequest(@JsonProperty("org_name") String orgName, String email, String password) {}
    public record LoginRequest(String email, String password) {}

    private static Map<String, Object> session(AuthService.Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", s.token());
        m.put("org_name", s.orgName());
        return m;
    }

    @PostMapping("/api/auth/register")
    public Map<String, Object> register(@RequestBody RegisterRequest body) {
        return session(auth.register(body.orgName(), body.email(), body.password()));
    }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest body) {
        return session(auth.login(body.email(), body.password()));
    }

    /** Protected by AdminAuthInterceptor (valid admin session required). */
    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(@RequestHeader("Authorization") String authorization) {
        auth.logout(authorization);
        return Map.of("ok", true);
    }
}
