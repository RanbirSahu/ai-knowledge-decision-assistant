package com.example.assistant.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Builds an organization's chunk list from pre-extracted page text (src/test/resources/extracted/<org>/*.txt, pages split by form feed). */
final class TestKb {
    private TestKb() {}

    private static final Map<String, List<String>> FILES = Map.of(
        "greenfield", List.of("Scholarship_Rules", "Admissions_FAQ", "Hostel_Policy"),
        "zenith", List.of("Returns_and_Refund_Policy", "Warranty_Policy", "Shipping_Guide"));
    private static long nextId = 1, nextDoc = 1;

    static String name(String org) { return org.equals("greenfield") ? "Greenfield University" : "Zenith Electronics"; }

    static synchronized List<KbChunk> load(String org) {
        List<KbChunk> rows = new ArrayList<>();
        for (String f : FILES.get(org)) {
            String text = read("/extracted/" + org + "/" + f + ".txt");
            List<String> pages = Arrays.asList(text.split("\f", -1));
            long doc = nextDoc++;
            for (Chunker.Chunk c : Chunker.chunk(pages)) rows.add(new KbChunk(nextId++, doc, f + ".pdf", c.page(), c.heading(), c.text()));
        }
        return rows;
    }

    private static String read(String path) {
        try (InputStream in = TestKb.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
