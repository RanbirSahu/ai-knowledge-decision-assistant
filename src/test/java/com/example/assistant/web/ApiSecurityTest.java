package com.example.assistant.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** End-to-end API tests: authentication, tenant isolation, upload limits, token handling, usage counters. */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:itest;DB_CLOSE_DELAY=-1", "app.data-dir=target/it-data", "app.anthropic-api-key="})
class ApiSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private final AtomicInteger counter = new AtomicInteger();

    record Org(String admin, String assistant) {}
    Org greenfield, zenith;

    // ------------------------------------------------------------------------------------------ helpers
    JsonNode call(MockHttpServletRequestBuilder rb, int expected) throws Exception {
        String s = mvc.perform(rb).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isEmpty() ? null : json.readTree(s);
    }

    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder rb, String token) { return rb.header("Authorization", "Bearer " + token); }

    Org register(String name) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("org_name", name, "email", "u" + counter.incrementAndGet() + "@t.example", "password", "Passw0rd!x"));
        JsonNode r = call(post("/api/auth/register").contentType("application/json").content(body), 200);
        String admin = r.get("token").asText();
        return new Org(admin, call(auth(get("/api/admin/assistant"), admin), 200).get("token").asText());
    }

    JsonNode upload(String adminToken, String name, byte[] data, int expected) throws Exception {
        return call(auth(multipart("/api/admin/documents").file(new MockMultipartFile("file", name, "application/pdf", data)), adminToken), expected);
    }

    void uploadSamples(Org org, String folder) throws Exception {
        try (var files = Files.list(Path.of("sample_data/pdfs", folder))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".pdf")).toList()) upload(org.admin(), p.getFileName().toString(), Files.readAllBytes(p), 200);
        }
    }

    JsonNode ask(String token, String q) throws Exception {
        return call(post("/api/ask").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("token", token, "question", q))), 200);
    }

    static byte[] pdf(int pages) throws Exception {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 1; i <= pages; i++) {
                PDPage p = new PDPage();
                d.addPage(p);
                try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(i + ". Section " + i);
                    cs.newLineAtOffset(0, -20);
                    cs.showText("Students must apply online before the deadline.");
                    cs.endText();
                }
            }
            d.save(out);
            return out.toByteArray();
        }
    }

    @BeforeAll
    void setUp() throws Exception {
        greenfield = register("Greenfield University");
        zenith = register("Zenith Electronics");
        uploadSamples(greenfield, "greenfield");
        uploadSamples(zenith, "zenith");
    }

    // ------------------------------------------------------------------------------------------ end-to-end + isolation
    @Test
    void multiConditionQuestionThroughTheRealApiAndRealPdfExtraction() throws Exception {
        JsonNode r = ask(greenfield.assistant(), "I am a 2nd year student with a 3.6 GPA and I already hold a sports scholarship. Can I get the merit scholarship?");
        assertEquals("NOT_ELIGIBLE", r.get("outcome").asText());
        assertEquals("Scholarship_Rules.pdf", r.get("references").get(0).get("document").asText());
        assertEquals(2, r.get("references").get(0).get("page").asInt());
    }

    @Test
    void eachAssistantOnlyKnowsItsOwnDocuments() throws Exception {
        assertEquals("NO_KNOWLEDGE", ask(zenith.assistant(), "What GPA do I need for the merit scholarship?").get("outcome").asText());
        assertEquals("NO_KNOWLEDGE", ask(zenith.assistant(), "What time does the hostel gate close?").get("outcome").asText());
        assertEquals("NO_KNOWLEDGE", ask(greenfield.assistant(), "How many days do I have to return an opened laptop?").get("outcome").asText());
        assertEquals("NO_KNOWLEDGE", ask(greenfield.assistant(), "Does the warranty cover liquid damage?").get("outcome").asText());
        assertTrue(ask(zenith.assistant(), "How long do I have to return an unopened item?").get("answer").asText().contains("30 days"));
    }

    @Test
    void orgCannotSeeOrDeleteAnotherOrgsDocuments() throws Exception {
        JsonNode zDocs = call(auth(get("/api/admin/documents"), zenith.admin()), 200).get("documents");
        JsonNode gDocs = call(auth(get("/api/admin/documents"), greenfield.admin()), 200).get("documents");
        for (JsonNode z : zDocs) for (JsonNode g : gDocs) assertNotEquals(z.get("filename").asText(), g.get("filename").asText());
        long zenithDocId = zDocs.get(0).get("id").asLong();
        call(auth(delete("/api/admin/documents/" + zenithDocId), greenfield.admin()), 404);
        assertEquals(zDocs.size(), call(auth(get("/api/admin/documents"), zenith.admin()), 200).get("documents").size());
    }

    // ------------------------------------------------------------------------------------------ authentication
    @Test
    void adminApiRequiresAuthentication() throws Exception {
        call(get("/api/admin/documents"), 401);
        call(get("/api/admin/assistant"), 401);
        call(get("/api/admin/usage"), 401);
        call(post("/api/admin/assistant/regenerate"), 401);
        call(delete("/api/admin/documents/1"), 401);
    }

    @Test
    void assistantTokenCannotOpenTheDashboardApiAndAdminTokenIsNotAnAssistantToken() throws Exception {
        call(auth(get("/api/admin/documents"), greenfield.assistant()), 401);
        call(auth(get("/api/admin/me"), greenfield.assistant()), 401);
        call(post("/api/ask").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("token", greenfield.admin(), "question", "hello"))), 401);
    }

    @Test
    void invalidTokenAndEmptyQuestion() throws Exception {
        call(post("/api/ask").contentType("application/json").content("{\"token\":\"ast_doesnotexist\",\"question\":\"hi\"}"), 401);
        call(post("/api/ask").contentType("application/json").content("{\"token\":\"nope\",\"question\":\"hi\"}"), 401);
        JsonNode r = call(post("/api/ask").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("token", zenith.assistant(), "question", "   "))), 400);
        assertTrue(r.get("detail").asText().toLowerCase().contains("question"));
        call(post("/api/ask").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("token", zenith.assistant(), "question", "x".repeat(2000)))), 400);
    }

    @Test
    void privateDocumentsAreNotServed() throws Exception {
        for (String path : new String[]{"/data/uploads/1/x.pdf", "/uploads/1/x.pdf", "/static/app.db", "/api/documents/1"})
            assertTrue(mvc.perform(get(path)).andReturn().getResponse().getStatus() >= 400);
    }

    @Test
    void regenerateInvalidatesTheOldToken() throws Exception {
        Org o = register("Temp Org");
        JsonNode fresh = call(auth(post("/api/admin/assistant/regenerate"), o.admin()), 200);
        assertNotEquals(o.assistant(), fresh.get("token").asText());
        call(post("/api/ask").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("token", o.assistant(), "question", "hello"))), 401);
        call(get("/api/assistant/" + fresh.get("token").asText()), 200);
        assertTrue(fresh.get("iframe_code").asText().contains(fresh.get("token").asText()));
    }

    @Test
    void logoutKillsTheSession() throws Exception {
        Org o = register("Logout Org");
        call(auth(get("/api/admin/me"), o.admin()), 200);
        call(auth(post("/api/auth/logout"), o.admin()), 200);
        call(auth(get("/api/admin/me"), o.admin()), 401);
    }

    // ------------------------------------------------------------------------------------------ upload limits
    @Test
    void uploadValidation() throws Exception {
        Org o = register("Limits Org");
        assertTrue(upload(o.admin(), "notes.txt", "hello".getBytes(), 400).get("detail").asText().contains("not a PDF"));
        assertTrue(upload(o.admin(), "fake.pdf", "this is not a pdf".getBytes(), 400).get("detail").asText().contains("valid PDF"));
        upload(o.admin(), "bad.pdf", "%PDF-1.4 garbage garbage".getBytes(), 400);
        upload(o.admin(), "empty.pdf", new byte[0], 400);
        JsonNode tooLong = upload(o.admin(), "long.pdf", pdf(21), 400);
        assertTrue(tooLong.get("detail").asText().contains("21 pages"));
        upload(o.admin(), "ok20.pdf", pdf(20), 200);
        byte[] big = new byte[10 * 1024 * 1024 + 10];
        System.arraycopy("%PDF-1.4\n".getBytes(), 0, big, 0, 9);
        assertTrue(upload(o.admin(), "big.pdf", big, 413).get("detail").asText().contains("10 MB"));
    }

    @Test
    void replaceDeleteAndDocumentCountLimit() throws Exception {
        Org o = register("Count Org");
        assertFalse(upload(o.admin(), "a.pdf", pdf(2), 200).get("replaced").asBoolean());
        assertTrue(upload(o.admin(), "a.pdf", pdf(3), 200).get("replaced").asBoolean());
        assertEquals(1, call(auth(get("/api/admin/documents"), o.admin()), 200).get("documents").size());
        for (int i = 1; i < 10; i++) upload(o.admin(), "d" + i + ".pdf", pdf(1), 200);
        assertTrue(upload(o.admin(), "eleventh.pdf", pdf(1), 400).get("detail").asText().toLowerCase().contains("limit"));
        long id = call(auth(get("/api/admin/documents"), o.admin()), 200).get("documents").get(0).get("id").asLong();
        call(auth(delete("/api/admin/documents/" + id), o.admin()), 200);
        upload(o.admin(), "eleventh.pdf", pdf(1), 200);
    }

    @Test
    void usageCounters() throws Exception {
        Org o = register("Usage Org");
        upload(o.admin(), "a.pdf", pdf(1), 200);
        ask(o.assistant(), "What must students do before the deadline?");
        ask(o.assistant(), "Tell me about quantum chromodynamics");
        JsonNode u = call(auth(get("/api/admin/usage"), o.admin()), 200);
        assertEquals(2, u.get("asked").asInt());
        assertEquals(1, u.get("answered").asInt());
        assertEquals(1, u.get("unable_to_answer").asInt());
    }
}
