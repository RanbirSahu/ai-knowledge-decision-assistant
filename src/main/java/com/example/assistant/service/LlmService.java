package com.example.assistant.service;

import com.example.assistant.AppProps;
import com.example.assistant.core.Criterion;
import com.example.assistant.core.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Anthropic Messages API client. The LLM is used ONLY for language: (1) understand/decompose, (2) write the final answer. */
@Service
public class LlmService implements LlmClient {
    private static final String API = "https://api.anthropic.com/v1/messages";
    private static final Set<String> OPS = Set.of(">=", ">", "<=", "<", "==", "!=");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private static final String UNDERSTAND_SYS = """
        You analyse a visitor question against policy excerpts. Output ONLY JSON, no prose:
        {"facts": {"<attribute>": <number|boolean>},
         "criteria": [{"attribute": "<snake_case>", "operator": ">=|>|<=|<|==|!=", "value": <number|boolean>,
                       "kind": "requirement|exclusion", "text": "<the policy sentence>"}]}
        facts = what the VISITOR stated about themselves (use snake_case attribute names shared with criteria).
        criteria = machine-checkable conditions found in the policy excerpts that decide the visitor's question.
        Only include a criterion if the excerpt states it explicitly. Never invent values. If the question is not a
        personal eligibility/decision question return {"facts": {}, "criteria": []}.""";

    private static final String ANSWER_SYS = """
        You are a helpful assistant for one organization. Answer ONLY from the CONTEXT and DECISIONS provided.
        Rules: be concise and friendly; never add facts that are not in the context; if DECISIONS contain an outcome, state it
        first and explain which conditions were met or not met using the DECISIONS (do not re-decide); if information a decision
        needs is missing, ask for exactly that; mention the source document and page in brackets like [Scholarship_Rules.pdf — Page 2].""";

    private final AppProps props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public LlmService(AppProps props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public boolean available() { return props.anthropicApiKey() != null && !props.anthropicApiKey().isBlank(); }

    private String call(String system, String user, int maxTokens) {
        if (!available()) throw new LlmException("No ANTHROPIC_API_KEY configured.");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", props.llmModel());
            body.put("max_tokens", maxTokens);
            body.put("system", system);
            body.put("messages", List.of(Map.of("role", "user", "content", user)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                .timeout(Duration.ofSeconds(40))
                .header("x-api-key", props.anthropicApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) throw new LlmException("LLM call failed: HTTP " + resp.statusCode());
            StringBuilder text = new StringBuilder();
            for (JsonNode block : mapper.readTree(resp.body()).path("content"))
                if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
            return text.toString();
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM call interrupted", e);
        } catch (Exception e) {
            throw new LlmException("LLM call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Understood understand(String question, String excerpts) {
        String raw = call(UNDERSTAND_SYS, "QUESTION:\n" + question + "\n\nPOLICY EXCERPTS:\n" + excerpts, 900);
        Matcher m = JSON_OBJECT.matcher(raw);
        if (!m.find()) throw new LlmException("LLM returned no JSON.");
        try {
            JsonNode root = mapper.readTree(m.group());
            Map<String, Object> facts = new LinkedHashMap<>();
            root.path("facts").fields().forEachRemaining(e -> {
                Object v = scalar(e.getValue());
                if (v != null) facts.put(e.getKey(), v);
            });
            List<Criterion> criteria = new ArrayList<>();
            for (JsonNode c : root.path("criteria")) {
                String op = c.path("operator").asText("");
                String attr = c.path("attribute").asText("");
                Object value = scalar(c.path("value"));
                if (OPS.contains(op) && !attr.isBlank() && value != null)
                    criteria.add(new Criterion("c" + (criteria.size() + 1), attr, op, value, c.path("kind").asText("requirement"), c.path("text").asText("")));
            }
            return new Understood(facts, criteria);
        } catch (Exception e) {
            throw new LlmException("LLM returned unparseable JSON.", e);
        }
    }

    private static Object scalar(JsonNode n) {
        if (n.isNumber()) return n.doubleValue();
        if (n.isBoolean()) return n.booleanValue();
        return null;
    }

    @Override
    public String writeAnswer(String question, String questionType, String context, String decisionsSummary) {
        String user = "QUESTION TYPE: " + questionType + "\nQUESTION: " + question + "\n\nCONTEXT:\n" + context
                + "\n\nDECISIONS:\n" + (decisionsSummary == null || decisionsSummary.isBlank() ? "none" : decisionsSummary);
        return call(ANSWER_SYS, user, 700).strip();
    }
}
