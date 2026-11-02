package com.example.assistant.core;

/** One searchable piece of a document. Always loaded for exactly one organization. */
public record KbChunk(long id, long docId, String document, int page, String heading, String text) {}
