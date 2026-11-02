package com.example.assistant.core;

import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.*;

/**
 * Offline question understanding (used when no LLM key is set or the LLM call fails).
 * extractFacts(question)  -> what the visitor told us about themselves
 * extractCriteria(text)   -> machine-checkable rules found in the policy text
 * The LLM path produces the SAME structures, so the System 1 models do not care which one was used.
 */
public final class Understanding {
    private Understanding() {}

    public static final Map<String, Integer> YEARS = Map.of("first", 1, "1st", 1, "second", 2, "2nd", 2, "third", 3, "3rd", 3,
            "fourth", 4, "4th", 4, "final", 4);

    public static final Map<String, String> ATTR_LABEL = Map.of(
            "gpa", "GPA", "year_of_study", "year of study", "has_sports_scholarship", "holds a sports scholarship",
            "annual_family_income", "annual family income", "days_since_purchase", "days since purchase",
            "months_since_purchase", "months since purchase", "has_receipt", "has the original receipt",
            "item_damaged", "item is damaged", "liquid_damage", "liquid damage");

    private static double num(String s) { return Double.parseDouble(s.replace(",", "")); }

    private static boolean negated(String t, int start) {
        String window = t.substring(Math.max(0, start - 25), start);
        return NEG.matcher(window).find();
    }

    private static Pattern p(String regex) { return Pattern.compile(regex); }

    private static final Pattern NEG = p("(?:\\bno\\b|\\bnot\\b|n't|\\bwithout\\b|\\bnever\\b|\\blost\\b)[^.,;]{0,18}$");
    private static final Pattern GPA1 = p("(\\d(?:\\.\\d+)?)\\s*(?:cgpa|gpa)");
    private static final Pattern GPA2 = p("(?:cgpa|gpa)(?:\\s*(?:of|is|=|:))?\\s*(\\d(?:\\.\\d+)?)");
    private static final Pattern YEAR1 = p("\\b(1st|2nd|3rd|4th|first|second|third|fourth|final)[- ]year");
    private static final Pattern YEAR2 = p("\\byear\\s*(\\d)\\b");
    private static final Pattern SPORTS = p("(?:sports?|athletic)\\s+scholarship");
    private static final Pattern INCOME = p("income[^.\\d]{0,20}?(?:inr|rs\\.?|₹)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*(lakhs?|lpa|k)?");
    private static final Pattern DAYS1 = p("(\\d+)\\s*(day|week|month)s?\\s*(?:ago|back|old)");
    private static final Pattern DAYS2 = p("after\\s+(\\d+)\\s*(day|week|month)s?");
    private static final Pattern RECEIPT_YES = p("(?:have|with|got|kept|has)\\s+(?:the\\s+|my\\s+|an?\\s+)?(?:original\\s+)?(?:receipt|proof of purchase)");
    private static final Pattern RECEIPT_NO = p("(?:no|lost|without|don't have|do not have|didn't keep|dont have)\\s+(?:the\\s+|my\\s+|a\\s+)?(?:original\\s+)?receipt");
    private static final Pattern NOT_DAMAGED = p("(?:not|isn't|is not|un)\\s*damaged|no damage|undamaged");
    private static final Pattern DAMAGED = p("damaged|broken|cracked");
    private static final Pattern NO_LIQUID = p("no (?:liquid|water) damage|never (?:got )?(?:wet|spilled)");
    private static final Pattern LIQUID = p("spill|liquid|water damage|dropped in water|got wet|\\bwet\\b");

    // ------------------------------------------------------------------------------------ facts from the question
    public static Map<String, Object> extractFacts(String q) {
        String t = q.toLowerCase(Locale.ROOT);
        Map<String, Object> f = new LinkedHashMap<>();
        Matcher m = GPA1.matcher(t);
        if (m.find()) f.put("gpa", Double.parseDouble(m.group(1)));
        else if ((m = GPA2.matcher(t)).find()) f.put("gpa", Double.parseDouble(m.group(1)));

        m = YEAR1.matcher(t);
        if (m.find()) f.put("year_of_study", (double) YEARS.get(m.group(1)));
        else if ((m = YEAR2.matcher(t)).find()) f.put("year_of_study", Double.parseDouble(m.group(1)));

        m = SPORTS.matcher(t);
        if (m.find()) f.put("has_sports_scholarship", !negated(t, m.start()));

        m = INCOME.matcher(t);
        if (m.find()) {
            String digits = m.group(1).replaceAll("^,+|,+$", "");
            if (!digits.isEmpty()) {
                double v = num(digits);
                String unit = m.group(2) == null ? "" : m.group(2);
                v *= unit.startsWith("lakh") || unit.startsWith("lpa") ? 100000 : unit.equals("k") ? 1000 : 1;
                f.put("annual_family_income", v);
            }
        }

        m = DAYS1.matcher(t);
        boolean found = m.find();
        if (!found) { m = DAYS2.matcher(t); found = m.find(); }
        if (found) {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2);
            int days = n * (unit.equals("day") ? 1 : unit.equals("week") ? 7 : 30);
            f.put("days_since_purchase", (double) days);
            f.put("months_since_purchase", unit.equals("month") ? (double) n : Math.round(days / 30.0 * 10) / 10.0);
        }

        if (RECEIPT_NO.matcher(t).find()) f.put("has_receipt", false);
        else if (RECEIPT_YES.matcher(t).find()) f.put("has_receipt", true);

        if (NOT_DAMAGED.matcher(t).find()) f.put("item_damaged", false);
        else if (DAMAGED.matcher(t).find()) f.put("item_damaged", true);

        if (NO_LIQUID.matcher(t).find()) f.put("liquid_damage", false);
        else if (LIQUID.matcher(t).find()) f.put("liquid_damage", true);
        return f;
    }

    // ------------------------------------------------------------------------------------ criteria from policy text
    private record Rule(Pattern pattern, BiFunction<Matcher, String, Criterion> build) {}

    private static Criterion c(String attr, String op, Object val, String sent, String kind) {
        return new Criterion(null, attr, op, val, kind, sent.strip());
    }

    private static Rule rule(String regex, BiFunction<Matcher, String, Criterion> b) { return new Rule(p(regex), b); }

    private static final String NUM = "(\\d(?:\\.\\d+)?)";
    private static final List<Rule> RULES = List.of(
        rule("minimum[^.;]{0,30}?gpa[^.;]{0,20}?" + NUM, (m, s) -> c("gpa", ">=", Double.parseDouble(m.group(1)), s, "requirement")),
        rule("gpa[^.;]{0,25}?(?:at least|minimum of|no lower than)\\s*" + NUM, (m, s) -> c("gpa", ">=", Double.parseDouble(m.group(1)), s, "requirement")),
        rule("at least[^.;]{0,20}?" + NUM + "\\s*gpa", (m, s) -> c("gpa", ">=", Double.parseDouble(m.group(1)), s, "requirement")),
        rule("(first|second|third|fourth|1st|2nd|3rd|4th)[- ]year[^.;]{0,25}?or (?:above|higher|later)",
             (m, s) -> c("year_of_study", ">=", (double) YEARS.get(m.group(1)), s, "requirement")),
        rule("sports? scholarship[^.;]{0,50}(?:not eligible|ineligible|cannot)", (m, s) -> c("has_sports_scholarship", "==", false, s, "exclusion")),
        rule("(?:not|never)[^.;]{0,40}?(?:hold|holding|receive|receiving|have)[^.;]{0,30}sports? scholarship",
             (m, s) -> c("has_sports_scholarship", "==", false, s, "exclusion")),
        rule("income[^.;]{0,40}?(below|under|less than|not exceed(?:ing)?|up to|at most)\\s*(?:inr|rs\\.?|₹)?\\s*([\\d,]+)",
             (m, s) -> c("annual_family_income", List.of("below", "under", "less than").contains(m.group(1)) ? "<" : "<=", num(m.group(2)), s, "requirement")),
        rule("within\\s+(\\d+)\\s+days", (m, s) -> c("days_since_purchase", "<=", Double.parseDouble(m.group(1)), s, "requirement")),
        rule("within\\s+(\\d+)\\s+months?", (m, s) -> c("months_since_purchase", "<=", Double.parseDouble(m.group(1)), s, "requirement")),
        rule("original receipt", (m, s) -> c("has_receipt", "==", true, s, "requirement")),
        rule("(?:not|un)\\s*damaged|free (?:from|of) damage|no (?:physical )?damage", (m, s) -> c("item_damaged", "==", false, s, "requirement")),
        rule("(?:does not cover|excludes?|not covered)[^.;]{0,40}liquid damage|liquid damage[^.;]{0,40}(?:not covered|excluded)",
             (m, s) -> c("liquid_damage", "==", false, s, "exclusion"))
    );

    public static List<Criterion> extractCriteria(String text) {
        List<Criterion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String sent : text.split("(?<=[.!?])\\s+")) {
            String low = sent.toLowerCase(Locale.ROOT);
            for (Rule r : RULES) {
                Matcher m = r.pattern().matcher(low);
                if (m.find()) {
                    Criterion c = r.build().apply(m, sent);
                    String key = c.attribute() + "|" + c.operator() + "|" + c.value();
                    if (seen.add(key)) out.add(c);
                }
            }
        }
        List<Criterion> numbered = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) numbered.add(out.get(i).withId("c" + (i + 1)));
        return numbered;
    }

    private static final Pattern TARGET = p("(?:get|apply for|qualify for|eligible for|receive|claim|return|replace|buy)\\s+(?:the\\s+|a\\s+|an\\s+|my\\s+)?"
            + "([a-z\\- ]{3,40}?)(?:\\?|$|,|\\.|\\s+(?:if|with|given|since|when|after|because|under|even|as|while))");

    /** What the visitor is asking to get / claim / return, e.g. "merit scholarship". */
    public static String targetPhrase(String q) {
        Matcher m = TARGET.matcher(q.toLowerCase(Locale.ROOT));
        return m.find() ? m.group(1).strip() : "";
    }
}
