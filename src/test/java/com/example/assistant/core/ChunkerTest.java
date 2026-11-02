package com.example.assistant.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ChunkerTest {
    @Test
    void sectionsKeepHeadingAndPage() {
        List<String> pages = List.of("Doc Title\n1. Overview\nHello world.\n", "2. Merit Scholarship\nCovers 50 percent.\nMinimum GPA of 3.5.\n");
        List<Chunker.Chunk> c = Chunker.chunk(pages);
        assertEquals(2, c.size());
        assertEquals("Overview", c.get(0).heading());
        assertEquals(1, c.get(0).page());
        assertEquals("Merit Scholarship", c.get(1).heading());
        assertEquals(2, c.get(1).page());
        assertTrue(c.get(1).text().contains("Minimum GPA of 3.5"));
    }

    @Test
    void longSectionsAreSplitWithOverlap() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) body.append("This is sentence number ").append(i).append(" with several filler words in it.\n");
        List<Chunker.Chunk> c = Chunker.chunk(List.of("1. Long Section\n" + body));
        assertTrue(c.size() > 1);
        assertTrue(c.stream().allMatch(x -> x.heading().equals("Long Section")));
    }

    @Test
    void sampleScholarshipRulesChunkOnPageTwo() {
        List<KbChunk> kb = TestKb.load("greenfield");
        KbChunk merit = kb.stream().filter(k -> k.heading().equals("Merit Scholarship")).findFirst().orElseThrow();
        assertEquals("Scholarship_Rules.pdf", merit.document());
        assertEquals(2, merit.page());
    }

    @Test
    void offlineCriteriaExtractionFindsThreeMeritRules() {
        List<KbChunk> kb = TestKb.load("greenfield");
        String merit = kb.stream().filter(k -> k.heading().equals("Merit Scholarship")).findFirst().orElseThrow().text();
        List<Criterion> cs = Understanding.extractCriteria(merit);
        assertEquals(List.of("gpa", "year_of_study", "has_sports_scholarship"), cs.stream().map(Criterion::attribute).toList());
    }
}
