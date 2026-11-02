package com.example.assistant.core;

import java.util.*;
import java.util.regex.*;

/** Tokenising / stemming helpers shared by the chunker, the retriever and the decision models. */
public final class Text {
    private Text() {}

    public static final Set<String> STOP = new HashSet<>(Arrays.asList((
            "a an the is are was were be been am do does did can could should would will shall may might must "
          + "i me my we our you your he she it its they their them this that these those of to in on at for with from by "
          + "as and or but if then than so not no yes any all some what which who whom when where why how there here "
          + "get got have has had about into over under per also just very more most much many tell please give").split("\\s+")));

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+(?:\\.[0-9]+)?");
    private static final Pattern NUMERIC = Pattern.compile("[0-9.]+");
    private static final Pattern SENT_SPLIT = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9])");

    public static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        if (w.length() > 8 && w.endsWith("ly")) return w.substring(0, w.length() - 2);   // internationally -> international
        return w;
    }

    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(stem(m.group()));
        return out;
    }

    /** Distinct meaningful terms: no stopwords, no bare numbers. */
    public static List<String> contentTerms(String text) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String raw = m.group(), t = stem(raw);
            if (STOP.contains(raw) || STOP.contains(t) || seen.contains(t) || NUMERIC.matcher(t).matches()) continue;
            seen.add(t);
            out.add(t);
        }
        return out;
    }

    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : SENT_SPLIT.split(text)) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    public static int wordCount(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }
}
