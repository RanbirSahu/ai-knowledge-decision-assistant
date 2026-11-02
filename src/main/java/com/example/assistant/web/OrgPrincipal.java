package com.example.assistant.web;

/** The organization behind a valid admin session. */
public record OrgPrincipal(long id, String name, String email, String assistantToken) {}
