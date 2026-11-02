package com.example.assistant.service;

import com.example.assistant.core.AskResult;
import com.example.assistant.core.Pipeline;
import com.example.assistant.web.ApiException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Glue between the web layer and the pure-Java pipeline: loads ONLY the tenant's chunks, runs the pipeline, counts usage. */
@Service
public class AskService {
    private static final Logger log = LoggerFactory.getLogger(AskService.class);
    public static final int MAX_QUESTION_CHARS = 1000;

    private final KbService kb;
    private final LlmService llm;
    private final JdbcTemplate jdbc;
    private final Pipeline pipeline = new Pipeline();

    public AskService(KbService kb, LlmService llm, JdbcTemplate jdbc) {
        this.kb = kb;
        this.llm = llm;
        this.jdbc = jdbc;
    }

    public Map<String, Object> ask(AuthService.OrgRef org, String rawQuestion) {
        String q = rawQuestion == null ? "" : rawQuestion.strip();
        if (q.isEmpty()) throw new ApiException(400, "Please type a question.");
        if (q.length() > MAX_QUESTION_CHARS) throw new ApiException(400, "Question is too long (max " + MAX_QUESTION_CHARS + " characters).");
        try {
            AskResult result = pipeline.ask(org.name(), kb.loadChunks(org.id()), q, llm);
            bump(org.id(), result.answered() ? "answered" : "unable");
            return result.toMap();
        } catch (RuntimeException e) {
            log.error("ask failed for org {}", org.id(), e);
            bump(org.id(), "errors");
            throw new ApiException(500, "Sorry, something went wrong while answering. Please try again in a moment.");   // never leak internals
        }
    }

    private void bump(long orgId, String kind) {
        String col = switch (kind) { case "answered" -> "answered"; case "unable" -> "unable"; default -> "errors"; };
        jdbc.update("UPDATE org_usage SET asked = asked + 1, " + col + " = " + col + " + 1 WHERE org_id = ?", orgId);
    }

    public Map<String, Object> usage(long orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT asked, answered, unable FROM org_usage WHERE org_id = ?", orgId);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        Map<String, Object> r = rows.isEmpty() ? Map.of("ASKED", 0, "ANSWERED", 0, "UNABLE", 0) : rows.get(0);
        m.put("asked", r.get("ASKED"));
        m.put("answered", r.get("ANSWERED"));
        m.put("unable_to_answer", r.get("UNABLE"));
        return m;
    }
}
