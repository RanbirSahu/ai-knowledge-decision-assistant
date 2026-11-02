package com.example.assistant.core;

import com.example.assistant.core.Bm25Index.Hit;
import com.example.assistant.core.Decisions.BoolResult;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic answer writer used when the LLM is unavailable: extractive, always cites its source. */
public final class Answerer {
    private Answerer() {}

    public record Combined(String outcome, List<BoolResult> results, Hit hit) {}

    private static String ref(Hit h) { return "[" + h.document() + " — Page " + h.page() + "]"; }

    static List<String> bestSentences(List<String> questionTerms, String text, int n) {
        List<String> sents = Text.sentences(text);
        record Sc(int count, int idx, String s) {}
        List<Sc> scored = new ArrayList<>();
        for (int i = 0; i < sents.size(); i++) {
            Set<String> toks = new HashSet<>(Text.tokenize(sents.get(i)));
            int c = 0;
            for (String t : questionTerms) if (toks.contains(t)) c++;
            scored.add(new Sc(c, i, sents.get(i)));
        }
        if (scored.isEmpty()) return List.of(text);
        List<Sc> sorted = new ArrayList<>(scored);
        sorted.sort((a, b) -> a.count() != b.count() ? Integer.compare(b.count(), a.count()) : Integer.compare(a.idx(), b.idx()));
        List<Sc> pick = new ArrayList<>();
        for (Sc s : sorted.subList(0, Math.min(n, sorted.size()))) if (s.count() > 0) pick.add(s);
        if (pick.isEmpty()) pick.add(scored.get(0));
        pick.sort(Comparator.comparingInt(Sc::idx));
        List<String> out = new ArrayList<>();
        for (Sc s : pick) out.add(s.s());
        return out;
    }

    public static String write(String qtype, List<String> questionTerms, List<Hit> hits, Combined combined) {
        if (combined != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(switch (combined.outcome()) {
                case "ELIGIBLE" -> "Yes, based on the policy you appear to be eligible.";
                case "NOT_ELIGIBLE" -> "No, based on the policy you are not eligible.";
                default -> "I can't give a final decision yet because some information is missing.";
            });
            List<String> missing = new ArrayList<>();
            for (BoolResult r : combined.results()) {
                String mark = r.result() == null ? "?" : r.result() ? "✔" : "✘";
                sb.append("\n").append(mark).append(" ").append(r.reason());
                if (r.result() == null) missing.add(Understanding.ATTR_LABEL.getOrDefault(r.attribute(), r.attribute()));
            }
            if (combined.outcome().equals("NEED_MORE_INFO") && !missing.isEmpty()) sb.append("\nPlease tell me: ").append(String.join(", ", missing)).append(".");
            sb.append("\nSource: ").append(ref(combined.hit()));
            return sb.toString();
        }
        if (qtype.equals("comparison")) {
            List<String> parts = new ArrayList<>();
            for (Hit h : hits.subList(0, Math.min(3, hits.size()))) {
                List<String> s = Text.sentences(h.text());
                parts.add("**" + (h.heading().isEmpty() ? h.document() : h.heading()) + "** — " + String.join(" ", s.subList(0, Math.min(2, s.size()))) + " " + ref(h));
            }
            return "Here is how they compare:\n\n" + String.join("\n\n", parts);
        }
        Hit h = hits.get(0);
        if (qtype.equals("procedural")) {
            String steps = Pattern.compile("\\s+(Step \\d+:)").matcher(h.text()).replaceAll("\n$1");
            return "Here is the process (" + h.heading() + "):\n" + steps + "\n" + ref(h);
        }
        return String.join(" ", bestSentences(questionTerms, h.text(), 3)) + " " + ref(h);
    }
}
