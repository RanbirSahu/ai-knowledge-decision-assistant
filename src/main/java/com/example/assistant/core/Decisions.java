package com.example.assistant.core;

import com.example.assistant.core.Bm25Index.Hit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * System 1 decision models. Each is a small, fast, deterministic function answering ONE narrow question and returning a typed
 * result. No LLM is involved. They are independent of each other, so the pipeline runs them in parallel.
 *
 *   QuestionClassifier -> CLASSIFICATION (what kind of question is this?)
 *   PolicyChooser      -> CHOICE         (which policy section governs?)
 *   CriterionEvaluator -> BOOLEAN        (is condition X satisfied?)  one call per criterion, in parallel
 *   SupportScorer      -> SCORE          (how strongly does the policy text support the outcome?)
 */
public final class Decisions {
    private Decisions() {}

    // ---------------------------------------------------------------------------------------------- result types
    public record Classification(String label, Map<String, Integer> scores) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision_type", "classification");
            m.put("label", label);
            m.put("scores", scores);
            return m;
        }
    }

    public record Ranked(double score, double headingMatch, Hit hit) {}

    public record Choice(Hit chosen, List<Ranked> ranked) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision_type", "choice");
            m.put("chosen", chosen == null ? null : (chosen.heading().isEmpty() ? chosen.document() : chosen.heading())
                    + " (" + chosen.document() + " — Page " + chosen.page() + ")");
            m.put("chunk_id", chosen == null ? null : chosen.chunkId());
            List<Map<String, Object>> opts = new ArrayList<>();
            for (Ranked r : ranked.subList(0, Math.min(4, ranked.size()))) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("section", r.hit().heading());
                o.put("document", r.hit().document());
                o.put("page", r.hit().page());
                o.put("score", Math.round(r.score() * 100) / 100.0);
                opts.add(o);
            }
            m.put("options", opts);
            return m;
        }
    }

    /** result: TRUE = condition satisfied (also for exclusions), FALSE = violated, null = unknown / could not be evaluated. */
    public record BoolResult(String criterionId, String attribute, String requirement, String kind, Object provided,
                             String sourceText, Boolean result, String reason, String error) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision_type", "boolean");
            m.put("criterion_id", criterionId);
            m.put("attribute", attribute);
            m.put("requirement", requirement);
            m.put("kind", kind);
            m.put("provided", provided);
            m.put("source_text", sourceText);
            m.put("error", error);
            m.put("result", result);
            m.put("reason", reason);
            return m;
        }
    }

    public record Score(double value) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision_type", "score");
            m.put("name", "policy_support");
            m.put("value", value);
            return m;
        }
    }

    // ---------------------------------------------------------------------------------------------- 1. Classification
    public static final class QuestionClassifier {
        private static final Map<String, List<Pattern>> PATTERNS = new LinkedHashMap<>();
        static {
            PATTERNS.put("comparison", pats("\\bdifference\\b", "\\bcompare\\b", "\\bversus\\b", "\\bvs\\b", "\\bdiffer", "\\bbetter\\b", "\\bwhich (?:one|is)\\b"));
            PATTERNS.put("procedural", pats("\\bhow (?:do|can|to|should)\\b", "\\bsteps?\\b", "\\bprocess\\b", "\\bprocedure\\b", "\\bsubmit\\b", "\\bapply\\b", "\\bregister\\b"));
            PATTERNS.put("eligibility", pats("\\bcan i\\b", "\\bam i\\b", "\\beligible\\b", "\\bqualify\\b", "\\bentitled\\b", "\\bwill i (?:get|be)\\b", "\\bis it covered\\b", "\\bcovered\\b"));
            PATTERNS.put("policy", pats("\\bpolicy\\b", "\\brules?\\b", "\\ballowed\\b", "\\bpermitted\\b", "\\bpenalt", "\\brefund\\b", "\\bcurfew\\b"));
        }

        private static List<Pattern> pats(String... regexes) {
            List<Pattern> l = new ArrayList<>();
            for (String r : regexes) l.add(Pattern.compile(r));
            return l;
        }

        public Classification classify(String question, int nFacts) {
            String q = question.toLowerCase(Locale.ROOT);
            Map<String, Integer> scores = new LinkedHashMap<>();
            for (var e : PATTERNS.entrySet()) {
                int s = 0;
                for (Pattern p : e.getValue()) if (p.matcher(q).find()) s++;
                scores.put(e.getKey(), s);
            }
            if (scores.get("eligibility") > 0 && nFacts >= 1) {
                scores.merge("eligibility", 2, Integer::sum);       // personal facts + eligibility cue => multi-condition decision
            } else if (scores.get("eligibility") > 0) {
                scores.put("eligibility", 0);                       // "can I ...?" with no personal facts is just a policy lookup
                scores.merge("policy", 1, Integer::sum);
            }
            if (scores.get("procedural") > 0 && !(scores.get("eligibility") > 0 && nFacts > 0)) scores.merge("procedural", 1, Integer::sum);
            String label = "direct_info";
            int best = 0;
            for (var e : scores.entrySet()) if (e.getValue() > best) { best = e.getValue(); label = e.getKey(); }
            return new Classification(label, scores);
        }
    }

    // ---------------------------------------------------------------------------------------------- 2. Choice
    /**
     * Pick which policy section governs the question. Heading overlap with the thing being asked about (e.g. "merit scholarship")
     * beats raw text similarity, which stops "Sports Scholarship" hijacking a merit question.
     */
    public static final class PolicyChooser {
        private static final Pattern PROCEDURAL_HEAD = Pattern.compile("how|step|process|apply|procedure|claim|start|register", Pattern.CASE_INSENSITIVE);

        public Choice choose(String question, List<Hit> hits, Bm25Index index, String qtype) {
            List<String> qTerms = Text.contentTerms(question);
            Set<String> tgt = new HashSet<>();
            if (qtype.equals("eligibility") || qtype.equals("policy") || qtype.equals("direct_info"))
                tgt.addAll(Text.contentTerms(Understanding.targetPhrase(question)));
            double top = hits.stream().mapToDouble(Hit::score).max().orElse(1.0);
            if (top == 0) top = 1.0;
            List<Ranked> ranked = new ArrayList<>();
            for (Hit h : hits) {
                List<String> head = Text.contentTerms(h.heading());
                double denom = 0, inQ = 0, inT = 0;
                for (String t : head) {
                    double w = index.idf(t);
                    denom += w;
                    if (qTerms.contains(t)) inQ += w;
                    if (tgt.contains(t)) inT += w;
                }
                if (denom == 0) denom = 1;
                double headQ = inQ / denom, headT = tgt.isEmpty() ? 0 : inT / denom;
                double bonus = qtype.equals("procedural") && PROCEDURAL_HEAD.matcher(h.heading()).find() ? 1.0 : 0;
                ranked.add(new Ranked(h.score() / top + headQ + 2.0 * headT + bonus, headQ, h));
            }
            ranked.sort((a, b) -> Double.compare(b.score(), a.score()));      // stable: ties keep retrieval order
            return new Choice(ranked.isEmpty() ? null : ranked.get(0).hit(), ranked);
        }
    }

    // ---------------------------------------------------------------------------------------------- 3. Boolean
    static String fmt(Object v) {
        if (v instanceof Boolean b) return b ? "yes" : "no";
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(v);
    }

    static String fmtRule(String op, Object want) {
        if (want instanceof Boolean b) return b ? "yes" : "no";
        String word = switch (op) {
            case ">=" -> "at least"; case ">" -> "more than"; case "<=" -> "at most"; case "<" -> "below";
            case "==" -> "exactly"; case "!=" -> "not"; default -> op;
        };
        return word + " " + fmt(want);
    }

    public static final class CriterionEvaluator {
        public BoolResult evaluate(Criterion c, Map<String, Object> facts) {
            String label = Understanding.ATTR_LABEL.getOrDefault(c.attribute(), c.attribute());
            Object have = facts.get(c.attribute());
            String requirement = label + " " + fmtRule(c.operator(), c.value());
            if (have == null)
                return new BoolResult(c.id(), c.attribute(), requirement, c.kind(), null, c.text(), null, "Your " + label + " was not stated.", null);
            try {
                boolean ok = compare(c.operator(), have, c.value());
                return new BoolResult(c.id(), c.attribute(), requirement, c.kind(), have, c.text(), ok,
                        label + ": you said " + fmt(have) + "; the policy requires " + fmtRule(c.operator(), c.value()) + " → " + (ok ? "met" : "NOT met") + ".", null);
            } catch (RuntimeException e) {      // a failing decision model is isolated to its own criterion
                return new BoolResult(c.id(), c.attribute(), requirement, c.kind(), have, c.text(), null, "Could not evaluate " + label + ".", String.valueOf(e.getMessage()));
            }
        }

        static boolean compare(String op, Object have, Object want) {
            if (have instanceof Boolean h && want instanceof Boolean w) {
                return switch (op) { case "==" -> h.equals(w); case "!=" -> !h.equals(w); default -> throw new IllegalArgumentException("bad operator for boolean: " + op); };
            }
            if (have instanceof Number h && want instanceof Number w) {
                double a = h.doubleValue(), b = w.doubleValue();
                return switch (op) {
                    case ">=" -> a >= b; case ">" -> a > b; case "<=" -> a <= b; case "<" -> a < b;
                    case "==" -> a == b; case "!=" -> a != b;
                    default -> throw new IllegalArgumentException("unknown operator: " + op);
                };
            }
            throw new IllegalArgumentException("type mismatch: " + have + " vs " + want);
        }
    }

    // ---------------------------------------------------------------------------------------------- 4. Score
    /** 0..1 confidence: 35% retrieval relevance + 35% share of criteria that could be evaluated + 30% agreement of the evaluated ones. */
    public static final class SupportScorer {
        public Score score(double relevance, List<BoolResult> results) {
            double val;
            if (results.isEmpty()) {
                val = relevance;
            } else {
                long evaluable = results.stream().filter(r -> r.result() != null).count();
                double coverage = (double) evaluable / results.size();
                long passed = results.stream().filter(r -> Boolean.TRUE.equals(r.result())).count();
                double agreement = evaluable > 0 ? (double) passed / evaluable : 0;
                val = 0.35 * relevance + 0.35 * coverage + 0.30 * Math.max(agreement, 1 - agreement);
            }
            return new Score(Math.round(Math.min(val, 1.0) * 100) / 100.0);
        }
    }
}
