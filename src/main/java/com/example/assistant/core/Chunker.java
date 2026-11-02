package com.example.assistant.core;

import java.util.*;
import java.util.regex.Pattern;

/** Heading-aware chunking. A section (heading + body) becomes a chunk; long bodies are split into sentence windows. */
public final class Chunker {
    private Chunker() {}

    public record Chunk(int page, String heading, String text) {}

    private static final Pattern HEAD_NUM = Pattern.compile("^\\d+(?:\\.\\d+)*[.)]?\\s+\\S.{1,80}$");
    private static final Pattern NUM_PREFIX = Pattern.compile("^\\d+(?:\\.\\d+)*[.)]?\\s+");
    private static final Set<String> SMALL = Set.of("and", "or", "of", "the", "for", "to", "a", "in", "on", "&");

    static boolean isHeading(String line, String prev) {
        line = line.strip();
        if (line.isEmpty() || line.length() > 90 || line.endsWith(".") || line.endsWith(",") || line.endsWith(";")) return false;
        String[] words = line.split("\\s+");
        if (HEAD_NUM.matcher(line).matches() && words.length <= 10) return true;
        if (words.length > 1 && words.length <= 6) {
            boolean allTitle = true;
            for (String w : words) {
                char c = w.charAt(0);
                if (Character.isLetter(c) && !(Character.isUpperCase(c) || SMALL.contains(w.toLowerCase(Locale.ROOT)))) allTitle = false;
            }
            return allTitle && (prev.isEmpty() || prev.endsWith("."));
        }
        return false;
    }

    private static final class State {
        final List<Chunk> chunks = new ArrayList<>();
        final List<String> buf = new ArrayList<>();
        String heading = "";
        int bufPage = 1;
        final int maxWords;

        State(int maxWords) { this.maxWords = maxWords; }

        void flush() {
            String body = String.join(" ", buf).strip();
            buf.clear();
            if (body.isEmpty()) return;
            List<String> window = new ArrayList<>();
            int count = 0;
            for (String s : Text.sentences(body)) {
                int w = Text.wordCount(s);
                if (!window.isEmpty() && count + w > maxWords) {
                    chunks.add(new Chunk(bufPage, heading, String.join(" ", window)));
                    String last = window.get(window.size() - 1);          // 1-sentence overlap
                    window = new ArrayList<>(List.of(last));
                    count = Text.wordCount(last);
                }
                window.add(s);
                count += w;
            }
            if (!window.isEmpty()) chunks.add(new Chunk(bufPage, heading, String.join(" ", window)));
        }
    }

    public static List<Chunk> chunk(List<String> pages) { return chunk(pages, 130); }

    public static List<Chunk> chunk(List<String> pages, int maxWords) {
        State st = new State(maxWords);
        for (int p = 1; p <= pages.size(); p++) {
            String prev = "";
            for (String raw : pages.get(p - 1).split("\\R")) {
                String line = raw.strip();
                if (isHeading(line, prev)) {
                    st.flush();
                    st.heading = NUM_PREFIX.matcher(line).replaceFirst("");
                    st.bufPage = p;
                } else if (!line.isEmpty()) {
                    if (st.buf.isEmpty()) st.bufPage = p;
                    st.buf.add(line);
                }
                prev = line;
            }
            st.flush();     // chunks never span pages, so page references stay exact
        }
        return st.chunks;
    }
}
