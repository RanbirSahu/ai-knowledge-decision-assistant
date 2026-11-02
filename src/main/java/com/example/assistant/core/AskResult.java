package com.example.assistant.core;

import java.util.*;

/** Everything /api/ask returns. toMap() fixes the exact JSON field names used by the chat UI. */
public record AskResult(String answer, boolean answered, String outcome, String questionType, List<Reference> references,
                        List<Map<String, Object>> decisions, List<Map<String, Object>> trace, List<String> notes, boolean llmUsed) {

    public record Reference(String document, int page, String section, String label, String snippet) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("document", document);
            m.put("page", page);
            m.put("section", section);
            m.put("label", label);
            m.put("snippet", snippet);
            return m;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("answer", answer);
        m.put("answered", answered);
        m.put("outcome", outcome);
        m.put("question_type", questionType);
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Reference r : references) refs.add(r.toMap());
        m.put("references", refs);
        m.put("decisions", decisions);
        m.put("trace", trace);
        m.put("notes", notes);
        m.put("llm_used", llmUsed);
        return m;
    }
}
