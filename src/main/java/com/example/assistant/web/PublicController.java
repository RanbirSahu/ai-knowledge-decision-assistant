package com.example.assistant.web;

import com.example.assistant.service.AskService;
import com.example.assistant.service.AuthService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public (no login) endpoints used by the chat page / embeds. Authorization = a valid assistant token, checked server-side. */
@RestController
public class PublicController {
    private final AuthService auth;
    private final AskService askService;

    public PublicController(AuthService auth, AskService askService) {
        this.auth = auth;
        this.askService = askService;
    }

    public record AskRequest(String token, String question) {}

    @GetMapping("/api/assistant/{token}")
    public Map<String, Object> info(@PathVariable("token") String token) {
        return Map.of("org_name", auth.orgByAssistantToken(token).name());
    }

    @PostMapping("/api/ask")
    public Map<String, Object> ask(@RequestBody AskRequest body) {
        AuthService.OrgRef org = auth.orgByAssistantToken(body.token());      // 1. validate token, identify tenant
        return askService.ask(org, body.question());                          // 2..n. org-scoped retrieval, decisions, answer
    }
}
