package com.example.assistant.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** The LLM code path with a fake provider: the LLM decomposes + writes, System 1 still decides. */
class LlmPathTest {
    private static final String Q = "I'm a third year student with a 3.2 GPA and no sports scholarship. Can I get the merit scholarship?";

    private static LlmClient fake(boolean failAnswer, boolean badOperator) {
        return new LlmClient() {
            int calls = 0;
            public boolean available() { return true; }
            public Understood understand(String q, String ex) {
                calls++;
                return new Understood(Map.of("gpa", 3.2, "year_of_study", 3.0, "has_sports_scholarship", false),
                        List.of(new Criterion("c1", "gpa", ">=", badOperator ? (Object) "abc" : (Object) 3.5, "requirement", "min GPA 3.5"),
                                new Criterion("c2", "year_of_study", ">=", 2.0, "requirement", "second year or above"),
                                new Criterion("c3", "has_sports_scholarship", "==", false, "exclusion", "no sports scholarship")));
            }
            public String writeAnswer(String q, String t, String c, String d) {
                calls++;
                if (failAnswer) throw new LlmException("LLM call failed: TimeoutException");
                return "LLM-WRITTEN: You are not eligible because your GPA is below 3.5. [Scholarship_Rules.pdf — Page 2]";
            }
        };
    }

    @Test
    void llmDecomposesSystem1DecidesLlmWrites() {
        AskResult r = new Pipeline().ask("Greenfield", TestKb.load("greenfield"), Q, fake(false, false));
        assertTrue(r.llmUsed());
        assertEquals("NOT_ELIGIBLE", r.outcome());                 // decided by Boolean models, not by LLM text
        assertTrue(r.answer().startsWith("LLM-WRITTEN"));
        List<Object> bools = new ArrayList<>();
        for (Map<String, Object> d : r.decisions()) if ("boolean".equals(d.get("decision_type"))) bools.add(d.get("result"));
        assertEquals(List.of(false, true, true), bools);
    }

    @Test
    void llmAnswerFailureFallsBackToOfflineWriter() {
        AskResult r = new Pipeline().ask("Greenfield", TestKb.load("greenfield"), Q, fake(true, false));
        assertEquals("NOT_ELIGIBLE", r.outcome());
        assertTrue(r.answer().contains("not eligible"));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("offline answer writer")), r.notes().toString());
    }

    @Test
    void decisionModelFailureIsReportedNotCrashed() {
        AskResult r = new Pipeline().ask("Greenfield", TestKb.load("greenfield"), Q, fake(false, true));
        assertTrue(r.answered());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("failed to run")), r.notes().toString());
        assertNotEquals("ELIGIBLE", r.outcome());
    }
}
