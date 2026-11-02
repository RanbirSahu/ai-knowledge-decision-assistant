package com.example.assistant.core;

import com.example.assistant.core.AskResult.Reference;
import com.example.assistant.core.Answerer.Combined;
import com.example.assistant.core.Bm25Index.Hit;
import com.example.assistant.core.Decisions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * The question pipeline (the orchestration / "application logic" layer).
 *
 *  organization's chunks -> retrieve -> [System 1] classify -> [System 1] choose policy section
 *     -> [LLM or offline] understand: facts + criteria
 *     -> [System 1, PARALLEL] one Boolean decision per criterion -> app logic COMBINES -> [System 1] score
 *     -> [LLM or offline] write answer -> answer + references
 *
 * This class has no web or database dependency: the caller passes ONLY the calling organization's chunks.
 */
public final class Pipeline {
    public static final double MIN_RELEVANCE = 0.35;      // weighted query-term coverage below this => "not in the knowledge base"
    private static final Pattern SIBLING_SECTIONS = Pattern.compile("exclusion|general|condition|eligib|requirement", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_PERSON = Pattern.compile("\\b(i|i'm|i've|my|me|we|our|mine)\\b");

    private final Decisions.QuestionClassifier classifier = new Decisions.QuestionClassifier();
    private final Decisions.PolicyChooser chooser = new Decisions.PolicyChooser();
    private final Decisions.CriterionEvaluator evaluator = new Decisions.CriterionEvaluator();
    private final Decisions.SupportScorer scorer = new Decisions.SupportScorer();

    /** ALL conditions must hold. Any failure -> NOT_ELIGIBLE; otherwise unknowns -> NEED_MORE_INFO. Pure application logic. */
    public static String combine(List<BoolResult> results) {
        if (results.stream().anyMatch(r -> Boolean.FALSE.equals(r.result()))) return "NOT_ELIGIBLE";
        if (results.stream().anyMatch(r -> r.result() == null)) return "NEED_MORE_INFO";
        return "ELIGIBLE";
    }

    private static final class Trace {
        final long t0 = System.nanoTime();
        final List<Map<String, Object>> steps = new ArrayList<>();
        void step(String name, Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", name);
            m.put("ms", (System.nanoTime() - t0) / 1_000_000);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
            steps.add(m);
        }
    }

    private static Reference ref(Hit h) {
        String t = h.text();
        return new Reference(h.document(), h.page(), h.heading(), h.document() + " — Page " + h.page(), t.length() > 220 ? t.substring(0, 220) + "…" : t);
    }

    public AskResult ask(String orgName, List<KbChunk> orgChunks, String question, LlmClient llm) {
        Trace trace = new Trace();
        trace.step("organization identified", "org", orgName);

        Map<String, Object> offlineFacts = Understanding.extractFacts(question);
        boolean personal = !offlineFacts.isEmpty() && FIRST_PERSON.matcher(question.toLowerCase(Locale.ROOT)).find();
        Bm25Index index = new Bm25Index(orgChunks);                       // ONLY this tenant's chunks are ever indexed
        List<Hit> hits = index.search(question, Bm25Index.TOP_K, personal);
        double bestRel = hits.stream().mapToDouble(Hit::relevance).max().orElse(0.0);
        List<String> top = new ArrayList<>();
        for (Hit h : hits.subList(0, Math.min(3, hits.size()))) top.add(h.document() + " p" + h.page() + " · " + h.heading());
        trace.step("knowledge retrieved", "chunks", hits.size(), "best_relevance", Math.round(bestRel * 100) / 100.0, "top", top);

        if (hits.isEmpty() || bestRel < MIN_RELEVANCE) {
            trace.step("no relevant knowledge");
            return new AskResult("I couldn't find anything about that in " + orgName + "'s knowledge base, so I'd rather not guess. "
                    + "Please contact the organization directly — your question has been flagged for human review.",
                    false, "NO_KNOWLEDGE", "unknown", List.of(), List.of(), trace.steps, List.of(), false);
        }

        List<String> qTerms = Text.contentTerms(question);
        Classification cls = classifier.classify(question, offlineFacts.size());
        String qtype = cls.label();
        trace.step("System 1: classification", "label", qtype, "scores", cls.scores());

        Choice choice = chooser.choose(question, hits, index, qtype);
        Hit chosen = choice.chosen();
        trace.step("System 1: policy choice", "chosen", choice.toMap().get("chosen"));

        List<Map<String, Object>> decisions = new ArrayList<>(List.of(cls.toMap(), choice.toMap()));
        List<String> notes = new ArrayList<>();
        boolean llmUsed = false;
        boolean llmOn = llm != null && llm.available();
        Combined combined = null;
        List<Hit> usedHits = new ArrayList<>(List.of(chosen));

        // ---- multi-condition decision path --------------------------------------------------------------------------
        if (qtype.equals("eligibility")) {
            List<Hit> sourceHits = new ArrayList<>(List.of(chosen));
            for (Hit h : index.chunksWhere(chosen.docId(), SIBLING_SECTIONS)) if (h.chunkId() != chosen.chunkId()) sourceHits.add(h);
            StringBuilder ex = new StringBuilder();
            for (Hit h : sourceHits) ex.append("[").append(h.document()).append(" — Page ").append(h.page()).append(" — ").append(h.heading()).append("] ").append(h.text()).append("\n\n");

            Map<String, Object> facts = new LinkedHashMap<>(offlineFacts);
            List<Criterion> criteria = new ArrayList<>();
            if (llmOn) {
                try {
                    LlmClient.Understood u = llm.understand(question, ex.toString().strip());     // LLM: understand / decompose
                    facts.putAll(u.facts());
                    criteria = new ArrayList<>(u.criteria());
                    llmUsed = true;
                    trace.step("LLM: understand + decompose", "facts", facts, "criteria", criteria.size());
                } catch (LlmClient.LlmException e) {
                    notes.add("LLM understanding unavailable (" + e.getMessage() + "); used the offline rule extractor.");
                } catch (RuntimeException e) {
                    notes.add("LLM output could not be parsed (" + e.getClass().getSimpleName() + "); used the offline rule extractor.");
                }
            }
            if (criteria.isEmpty()) {
                for (Hit h : sourceHits)
                    for (Criterion c : Understanding.extractCriteria(h.text())) criteria.add(c.withId("c" + (criteria.size() + 1)));
                trace.step("offline understand + decompose", "facts", facts, "criteria", criteria.size());
            }
            if (!criteria.isEmpty()) {
                List<BoolResult> results = evaluateInParallel(criteria, facts);                 // independent => parallel
                List<Object> brief = new ArrayList<>();
                for (BoolResult r : results) brief.add(Map.of("rule", r.requirement(), "result", String.valueOf(r.result())));
                trace.step("System 1: boolean decisions (parallel)", "n", results.size(), "results", brief);
                if (results.stream().anyMatch(r -> r.error() != null)) notes.add("One or more decision checks failed to run; the result may be incomplete.");
                String outcome = combine(results);
                Score score = scorer.score(bestRel, results);
                trace.step("application logic: combine", "outcome", outcome, "support_score", score.value());
                combined = new Combined(outcome, results, chosen);
                for (BoolResult r : results) decisions.add(r.toMap());
                decisions.add(score.toMap());
                for (Hit h : sourceHits.subList(1, sourceHits.size()))
                    for (BoolResult r : results)
                        if (!r.sourceText().isEmpty() && h.text().contains(r.sourceText())) { usedHits.add(h); break; }
            } else {
                notes.add("No machine-checkable conditions were found, so this was answered as a plain policy question.");
                qtype = "policy";
            }
        }

        // ---- non-decision paths: the sections the Choice model ranked highest --------------------------------------
        if (combined == null) {
            double bestS = choice.ranked().get(0).score();
            usedHits = new ArrayList<>();
            if (qtype.equals("comparison")) {         // one section per thing being compared (those whose heading the question names)
                List<Hit> named = new ArrayList<>(), all = new ArrayList<>();
                for (Ranked r : choice.ranked()) { all.add(r.hit()); if (r.headingMatch() >= 0.5) named.add(r.hit()); }
                List<Hit> pool = named.size() >= 2 ? named : all;
                usedHits.addAll(pool.subList(0, Math.min(3, pool.size())));
            } else {
                usedHits.add(chosen);
                int extra = 0;
                for (Ranked r : choice.ranked().subList(1, choice.ranked().size()))
                    if (r.score() >= 0.8 * bestS && extra++ < 2) usedHits.add(r.hit());
            }
        }

        // ---- answer generation -------------------------------------------------------------------------------------
        StringBuilder summary = new StringBuilder();
        if (combined != null) {
            summary.append("OUTCOME: ").append(combined.outcome());
            for (BoolResult r : combined.results()) summary.append("\n- ").append(r.reason()).append(" (rule: \"").append(r.sourceText()).append("\")");
        }
        StringBuilder ctx = new StringBuilder();
        for (Hit h : usedHits) ctx.append("[").append(h.document()).append(" — Page ").append(h.page()).append(" — ").append(h.heading()).append("] ").append(h.text()).append("\n\n");

        String answer = null;
        if (llmOn) {
            try {
                answer = llm.writeAnswer(question, qtype, ctx.toString().strip(), summary.toString());
                llmUsed = true;
                trace.step("LLM: answer generated");
            } catch (RuntimeException e) {
                notes.add("LLM answer generation failed (" + e.getMessage() + "); used the offline answer writer.");
            }
        }
        if (answer == null) {
            answer = Answerer.write(qtype, qTerms, usedHits, combined);
            if (combined == null && !qtype.equals("comparison")) usedHits = new ArrayList<>(usedHits.subList(0, 1));   // offline writer only uses the top section
            trace.step("offline answer generated");
        }

        List<Reference> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Hit h : usedHits) if (seen.add(h.document() + "#" + h.page())) refs.add(ref(h));
        return new AskResult(answer, true, combined != null ? combined.outcome() : "ANSWERED", qtype, refs, decisions, trace.steps, notes, llmUsed);
    }

    private List<BoolResult> evaluateInParallel(List<Criterion> criteria, Map<String, Object> facts) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, criteria.size()));
        try {
            List<Future<BoolResult>> futures = new ArrayList<>();
            for (Criterion c : criteria) futures.add(pool.submit(() -> evaluator.evaluate(c, facts)));
            List<BoolResult> out = new ArrayList<>();
            for (Future<BoolResult> f : futures) {
                try {
                    out.add(f.get(5, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new IllegalStateException("decision model failed", e);
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
