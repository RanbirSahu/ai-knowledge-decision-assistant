package com.example.assistant.core;

import java.util.*;
import java.util.regex.Pattern;

/** In-memory BM25 over ONE organization's chunks (the caller only ever passes that org's rows). */
public final class Bm25Index {
    public static final int TOP_K = 5;

    public record Hit(KbChunk chunk, double score, double relevance) {
        public long chunkId() { return chunk.id(); }
        public long docId() { return chunk.docId(); }
        public String document() { return chunk.document(); }
        public int page() { return chunk.page(); }
        public String heading() { return chunk.heading(); }
        public String text() { return chunk.text(); }
    }

    private final List<KbChunk> rows;
    private final List<List<String>> toks = new ArrayList<>();
    private final int n;
    private final double avgdl;
    private final Map<String, Integer> df = new HashMap<>();

    public Bm25Index(List<KbChunk> rows) {
        this.rows = rows;
        this.n = rows.size();
        double total = 0;
        for (KbChunk r : rows) {
            List<String> t = new ArrayList<>(Text.tokenize(r.heading()));
            t.addAll(Text.tokenize(r.heading()));            // heading counts double
            t.addAll(Text.tokenize(r.text()));
            toks.add(t);
            total += t.size();
            for (String w : new HashSet<>(t)) df.merge(w, 1, Integer::sum);
        }
        this.avgdl = n == 0 ? 1 : total / n;
    }

    public int size() { return n; }

    public boolean seen(String term) { return df.containsKey(term); }

    public double idf(String term) {
        int d = df.getOrDefault(term, 0);
        return Math.log(1 + (n - d + 0.5) / (d + 0.5));
    }

    public List<Hit> search(String query, int k, boolean personal) {
        if (n == 0) return List.of();
        List<String> q = Text.contentTerms(query);
        if (q.isEmpty()) return List.of();
        double k1 = 1.5, b = 0.75;
        List<double[]> scored = new ArrayList<>();            // [score, index]
        for (int i = 0; i < n; i++) {
            List<String> t = toks.get(i);
            Map<String, Integer> tf = new HashMap<>();
            for (String w : t) tf.merge(w, 1, Integer::sum);
            double s = 0;
            for (String term : q) {
                int f = tf.getOrDefault(term, 0);
                if (f > 0) s += idf(term) * f * (k1 + 1) / (f + k1 * (1 - b + b * t.size() / avgdl));
            }
            if (s > 0) scored.add(new double[]{s, i});
        }
        scored.sort((x, y) -> x[0] != y[0] ? Double.compare(y[0], x[0]) : Double.compare(y[1], x[1]));
        List<Hit> hits = new ArrayList<>();
        for (int j = 0; j < Math.min(k, scored.size()); j++) {
            int i = (int) scored.get(j)[1];
            KbChunk r = rows.get(i);
            double rel = relevance(q, new HashSet<>(toks.get(i)), personal);
            List<String> head = Text.contentTerms(r.heading());
            int matched = 0;
            for (String h : head) if (q.contains(h)) matched++;
            if (!head.isEmpty() && (matched == head.size() || matched >= 2)) rel = Math.max(rel, 0.5);   // question names the section itself
            hits.add(new Hit(r, scored.get(j)[0], rel));
        }
        return hits;
    }

    /**
     * IDF-weighted share of the question's meaningful terms found in the chunk. Terms absent from the whole corpus get max IDF,
     * so "astrophysics" hurts coverage while "university" barely counts. In "personal story" questions unseen words are ignored.
     */
    double relevance(List<String> qTerms, Set<String> chunkTokens, boolean personal) {
        double maxIdf = Math.log(1 + n + 0.5);
        double unseenW = personal ? 0.0 : maxIdf;
        double tot = 0, got = 0;
        int unseen = 0;
        for (String t : qTerms) {
            boolean known = df.containsKey(t);
            if (!known) unseen++;
            double w = known ? idf(t) : unseenW;
            tot += w;
            if (chunkTokens.contains(t)) got += w;
        }
        double score = tot > 0 ? got / tot : 0.0;
        if (!personal && !qTerms.isEmpty() && (double) unseen / qTerms.size() >= 0.5) score = Math.min(score, 0.3);
        return score;
    }

    /** Chunks of one document whose heading matches the regex (used to pull in "Exclusions" / "Conditions" sections). */
    public List<Hit> chunksWhere(long docId, Pattern headingRegex) {
        List<Hit> out = new ArrayList<>();
        for (KbChunk r : rows) if (r.docId() == docId && headingRegex.matcher(r.heading()).find()) out.add(new Hit(r, 0.0, 0.0));
        return out;
    }
}
