package com.example.assistant.core;

import java.util.List;
import java.util.Map;

/** The only things the LLM is ever asked to do: understand/decompose a question, and write the final answer. */
public interface LlmClient {
    boolean available();

    /** Extract the visitor's facts and the policy's machine-checkable criteria. */
    Understood understand(String question, String excerpts);

    /** Write the user-facing answer from context + already-made decisions. */
    String writeAnswer(String question, String questionType, String context, String decisionsSummary);

    record Understood(Map<String, Object> facts, List<Criterion> criteria) {}

    class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}
