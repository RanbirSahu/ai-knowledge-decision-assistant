package com.example.assistant.web;

import com.example.assistant.AppProps;
import com.example.assistant.service.AskService;
import com.example.assistant.service.AuthService;
import com.example.assistant.service.KbService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Everything under /api/admin/** requires a valid admin session (see AdminAuthInterceptor) and is always scoped to that org. */
@RestController
public class AdminController {
    private final KbService kb;
    private final AuthService auth;
    private final AskService askService;
    private final AppProps props;

    public AdminController(KbService kb, AuthService auth, AskService askService, AppProps props) {
        this.kb = kb;
        this.auth = auth;
        this.askService = askService;
        this.props = props;
    }

    @GetMapping("/api/admin/me")
    public Map<String, Object> me(@RequestAttribute("org") OrgPrincipal org) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("org_name", org.name());
        m.put("email", org.email());
        m.put("limits", Map.of("max_docs", KbService.MAX_DOCS, "max_pages", KbService.MAX_PAGES, "max_mb", KbService.MAX_BYTES / 1024 / 1024));
        return m;
    }

    @GetMapping("/api/admin/documents")
    public Map<String, Object> documents(@RequestAttribute("org") OrgPrincipal org) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documents", kb.listDocuments(org.id()));
        m.put("max_docs", KbService.MAX_DOCS);
        return m;
    }

    @PostMapping("/api/admin/documents")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, @RequestAttribute("org") OrgPrincipal org) throws IOException {
        KbService.UploadResult r = kb.addDocument(org.id(), file.getOriginalFilename(), file.getBytes());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("filename", r.filename());
        m.put("pages", r.pages());
        m.put("chunks", r.chunks());
        m.put("replaced", r.replaced());
        return m;
    }

    @DeleteMapping("/api/admin/documents/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id, @RequestAttribute("org") OrgPrincipal org) {
        kb.deleteDocument(org.id(), id);
        return Map.of("ok", true);
    }

    private Map<String, Object> assistant(String token) {
        String url = props.baseUrl() + "/a/" + token;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("url", url);
        m.put("iframe_code", "<iframe src=\"" + url + "\" width=\"400\" height=\"600\" style=\"border:1px solid #ddd;border-radius:12px\" title=\"Assistant\"></iframe>");
        m.put("script_code", "<script src=\"" + props.baseUrl() + "/static/embed.js\" data-token=\"" + token + "\" defer></script>");
        return m;
    }

    @GetMapping("/api/admin/assistant")
    public Map<String, Object> assistant(@RequestAttribute("org") OrgPrincipal org) {
        return assistant(auth.assistantTokenOf(org.id()));      // read fresh so a regenerated token shows immediately
    }

    @PostMapping("/api/admin/assistant/regenerate")
    public Map<String, Object> regenerate(@RequestAttribute("org") OrgPrincipal org) {
        return assistant(auth.regenerateAssistantToken(org.id()));
    }

    @GetMapping("/api/admin/usage")
    public Map<String, Object> usage(@RequestAttribute("org") OrgPrincipal org) {
        return askService.usage(org.id());
    }
}
