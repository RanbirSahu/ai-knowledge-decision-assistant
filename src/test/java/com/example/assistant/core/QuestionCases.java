package com.example.assistant.core;

import java.util.List;

/** GENERATED from sample_data/test_questions.json - the 23 sample questions (incl. cross-tenant isolation probes). */
final class QuestionCases {
    private QuestionCases() {}

    record Case(String id, String org, String question, String expectOutcome, boolean expectAnswered, String[] expectContains, String expectRef) {}

    static final List<Case> ALL = List.of(
        new Case("G1", "greenfield", "What is the application fee?", null, true, new String[]{"1500"}, "Admissions_FAQ.pdf"),
        new Case("G2", "greenfield", "What is the application deadline for undergraduate admission?", null, true, new String[]{"30 June"}, "Admissions_FAQ.pdf"),
        new Case("G3", "greenfield", "What time does the hostel gate close on weekdays?", null, true, new String[]{"10 PM"}, "Hostel_Policy.pdf"),
        new Case("G4", "greenfield", "How do I apply for a scholarship?", null, true, new String[]{"Step 1"}, "Scholarship_Rules.pdf"),
        new Case("G5", "greenfield", "I am a 2nd year student with a 3.6 GPA and I already hold a sports scholarship. Can I get the merit scholarship?", "NOT_ELIGIBLE", false, new String[]{}, "Scholarship_Rules.pdf"),
        new Case("G6", "greenfield", "I am a third year student with a GPA of 3.8 and no sports scholarship. Can I get the merit scholarship?", "ELIGIBLE", false, new String[]{}, "Scholarship_Rules.pdf"),
        new Case("G7", "greenfield", "My family income is 4 lakh per year and my GPA is 2.8. Am I eligible for the need-based grant?", "ELIGIBLE", false, new String[]{}, "Scholarship_Rules.pdf"),
        new Case("G8", "greenfield", "Can I get the merit scholarship? I have a 3.7 GPA.", "NEED_MORE_INFO", false, new String[]{}, null),
        new Case("G9", "greenfield", "What is the difference between the merit scholarship and the sports scholarship?", null, true, new String[]{"50 percent", "70 percent"}, null),
        new Case("G10", "greenfield", "What is the cafeteria menu on Friday?", "NO_KNOWLEDGE", false, new String[]{}, null),
        new Case("G11", "greenfield", "Does the university offer a PhD in astrophysics?", "NO_KNOWLEDGE", false, new String[]{}, null),
        new Case("Z1", "zenith", "How long do I have to return an unopened item?", null, true, new String[]{"30 days"}, "Returns_and_Refund_Policy.pdf"),
        new Case("Z2", "zenith", "I bought headphones 10 days ago and opened them. They are undamaged and I have the receipt. Can I return them?", "ELIGIBLE", false, new String[]{}, "Returns_and_Refund_Policy.pdf"),
        new Case("Z3", "zenith", "I bought a laptop 20 days ago and opened it. It is undamaged and I have the receipt. Can I return it?", "NOT_ELIGIBLE", false, new String[]{}, "Returns_and_Refund_Policy.pdf"),
        new Case("Z4", "zenith", "I bought a phone 8 months ago and it stopped working after I spilled water on it. Is it covered under warranty?", "NOT_ELIGIBLE", false, new String[]{}, "Warranty_Policy.pdf"),
        new Case("Z5", "zenith", "What is the difference between the standard warranty and the extended protection plan?", null, true, new String[]{"12 months", "24 more months"}, null),
        new Case("Z6", "zenith", "How do I start a return?", null, true, new String[]{"Step 1"}, "Returns_and_Refund_Policy.pdf"),
        new Case("Z7", "zenith", "Do you ship internationally?", null, true, new String[]{"not available"}, "Shipping_Guide.pdf"),
        new Case("Z8", "zenith", "What is the price of the Zenith X1 laptop?", "NO_KNOWLEDGE", false, new String[]{}, null),
        new Case("I1", "zenith", "What GPA do I need for the merit scholarship?", "NO_KNOWLEDGE", false, new String[]{}, null),
        new Case("I2", "zenith", "What time does the hostel gate close?", "NO_KNOWLEDGE", false, new String[]{}, null),
        new Case("I3", "greenfield", "How many days do I have to return an opened laptop?", "NO_KNOWLEDGE", false, new String[]{}, null),
        new Case("I4", "greenfield", "Does the warranty cover liquid damage?", "NO_KNOWLEDGE", false, new String[]{}, null)
    );
}
