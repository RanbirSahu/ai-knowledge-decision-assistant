package com.example.assistant.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PageController {
    private static ResponseEntity<Resource> html(String file) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(new ClassPathResource("static/" + file));
    }

    /** Admin dashboard (login / register / manage). */
    @GetMapping("/")
    public ResponseEntity<Resource> dashboard() { return html("dashboard.html"); }

    /** Public chat page. Same static shell for every token; it fetches /api/assistant/{token} itself, so no document content is embedded here. */
    @GetMapping("/a/{token}")
    public ResponseEntity<Resource> assistant() { return html("chat.html"); }
}
