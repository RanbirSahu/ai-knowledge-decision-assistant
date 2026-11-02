package com.example.assistant.core;

/** A machine-checkable condition found in a policy, e.g. gpa >= 3.5. value is a Double or Boolean. */
public record Criterion(String id, String attribute, String operator, Object value, String kind, String text) {
    public Criterion withId(String newId) { return new Criterion(newId, attribute, operator, value, kind, text); }
}
