package com.example.assistant.core;

import static org.junit.jupiter.api.Assertions.*;

import com.example.assistant.core.QuestionCases.Case;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Runs all sample questions through the real pipeline on the offline (no-LLM) path. */
class PipelineQuestionsTest {
    private final Pipeline pipeline = new Pipeline();

    private AskResult ask(String org, String q) {
        return pipeline.ask(TestKb.name(org), TestKb.load(org), q, null);
    }

    @Test
    void allSampleQuestionsBehaveAsExpected() {
        List<String> failures = new ArrayList<>();
        for (Case c : QuestionCases.ALL) {
            AskResult r = ask(c.org(), c.question());
            if (c.expectOutcome() != null && !c.expectOutcome().equals(r.outcome())) failures.add(c.id() + ": outcome " + r.outcome() + " != " + c.expectOutcome() + " :: " + r.answer());
            if (c.expectAnswered() && !r.answered()) failures.add(c.id() + ": expected an answer :: " + r.answer());
            for (String needle : c.expectContains())
                if (!r.answer().toLowerCase().contains(needle.toLowerCase())) failures.add(c.id() + ": answer lacks '" + needle + "' :: " + r.answer());
            if (c.expectRef() != null && r.references().stream().noneMatch(x -> x.document().equals(c.expectRef()))) failures.add(c.id() + ": missing reference " + c.expectRef() + " got " + r.references());
            if (r.outcome().equals("NO_KNOWLEDGE") && !r.references().isEmpty()) failures.add(c.id() + ": cited sources for an unanswerable question");
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void referencesNeverLeakAcrossOrganizations() {
        Map<String, Set<String>> own = new HashMap<>();
        for (String org : List.of("greenfield", "zenith")) {
            Set<String> names = new HashSet<>();
            for (KbChunk k : TestKb.load(org)) names.add(k.document());
            own.put(org, names);
        }
        String[] probes = {"merit scholarship GPA", "warranty liquid damage", "hostel fees", "return opened laptop", "application deadline",
                "shipping charges", "what is covered", "refund timeline"};
        for (String org : own.keySet())
            for (String q : probes)
                for (AskResult.Reference ref : ask(org, q).references())
                    assertTrue(own.get(org).contains(ref.document()), org + " leaked " + ref.document() + " for '" + q + "'");
    }

    @Test
    void multiConditionQuestionUsesSeveralDecisionTypes() {
        AskResult r = ask("greenfield", "I am a 2nd year student with a 3.6 GPA and I already hold a sports scholarship. Can I get the merit scholarship?");
        assertEquals("NOT_ELIGIBLE", r.outcome());
        Set<Object> types = new HashSet<>();
        for (Map<String, Object> d : r.decisions()) types.add(d.get("decision_type"));
        assertTrue(types.containsAll(Set.of("classification", "choice", "boolean", "score")), types.toString());
        List<Object> bools = new ArrayList<>();
        for (Map<String, Object> d : r.decisions()) if ("boolean".equals(d.get("decision_type"))) bools.add(d.get("result"));
        assertEquals(List.of(true, true, false), bools);      // gpa ok, year ok, sports-scholarship exclusion violated
    }

    @Test
    void usageIsNotNeededForEmptyKnowledgeBase() {
        AskResult r = pipeline.ask("Empty Org", List.of(), "What are your opening hours?", null);
        assertEquals("NO_KNOWLEDGE", r.outcome());
        assertFalse(r.answered());
    }
}
