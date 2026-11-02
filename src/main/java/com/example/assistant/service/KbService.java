package com.example.assistant.service;

import com.example.assistant.AppProps;
import com.example.assistant.core.Chunker;
import com.example.assistant.core.KbChunk;
import com.example.assistant.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Knowledge base: PDF validation, text extraction, chunking, storage. Every query is scoped by org_id. */
@Service
public class KbService {
    public static final int MAX_DOCS = 10;
    public static final int MAX_PAGES = 20;
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Path uploadRoot;

    public KbService(JdbcTemplate jdbc, TransactionTemplate tx, AppProps props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.uploadRoot = Path.of(props.dataDir(), "uploads").toAbsolutePath();   // private: no route ever serves this folder
    }

    public record UploadResult(String filename, int pages, int chunks, boolean replaced) {}

    // ------------------------------------------------------------------------------------------ validation + extraction
    static String sanitize(String name) {
        String n = name == null ? "" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^\\w.\\- ()]", "_");
        return n.isBlank() ? "upload.pdf" : n;
    }

    static List<String> validateAndExtract(String filename, byte[] data) {
        if (!filename.toLowerCase().endsWith(".pdf")) throw new ApiException(400, "'" + filename + "' is not a PDF file. Only .pdf files are accepted.");
        if (data.length == 0) throw new ApiException(400, "'" + filename + "' is empty.");
        if (data.length > MAX_BYTES)
            throw new ApiException(413, String.format("'%s' is %.1f MB; the limit is %d MB per PDF.", filename, data.length / 1024.0 / 1024.0, MAX_BYTES / 1024 / 1024));
        if (data.length < 5 || data[0] != '%' || data[1] != 'P' || data[2] != 'D' || data[3] != 'F')
            throw new ApiException(400, "'" + filename + "' is not a valid PDF (bad file header).");
        List<String> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(data)) {
            int n = doc.getNumberOfPages();
            if (n > MAX_PAGES) throw new ApiException(400, "'" + filename + "' has " + n + " pages; the limit is " + MAX_PAGES + " pages per PDF.");
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= n; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(doc));
            }
        } catch (ApiException e) {
            throw e;
        } catch (InvalidPasswordException e) {
            throw new ApiException(400, "'" + filename + "' is password-protected. Remove the password and upload again.");
        } catch (IOException | RuntimeException e) {
            throw new ApiException(400, "'" + filename + "' could not be read as a PDF (" + e.getClass().getSimpleName() + "). The file may be corrupt.");
        }
        if (pages.stream().allMatch(String::isBlank))
            throw new ApiException(400, "'" + filename + "' contains no extractable text (scanned image PDF?). OCR is not supported in this MVP.");
        return pages;
    }

    // ------------------------------------------------------------------------------------------ add / replace
    public UploadResult addDocument(long orgId, String originalName, byte[] data) {
        String filename = sanitize(originalName);
        List<String> pages = validateAndExtract(filename, data);
        List<Chunker.Chunk> chunks = Chunker.chunk(pages);

        Path stored = uploadRoot.resolve(String.valueOf(orgId)).resolve(UUID.randomUUID().toString().replace("-", "") + ".pdf");
        try {
            UploadResult result = tx.execute(status -> {
                int count = jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE org_id = ?", Integer.class, orgId);
                List<Map<String, Object>> existing = jdbc.queryForList("SELECT id, stored_path FROM documents WHERE org_id = ? AND filename = ?", orgId, filename);
                if (existing.isEmpty() && count >= MAX_DOCS)
                    throw new ApiException(400, "Document limit reached (" + MAX_DOCS + "). Delete a document before uploading another.");
                boolean replaced = !existing.isEmpty();
                for (Map<String, Object> old : existing) {                  // same filename => replacement
                    jdbc.update("DELETE FROM documents WHERE id = ? AND org_id = ?", old.get("id"), orgId);
                    deleteQuietly(Path.of((String) old.get("stored_path")));
                }
                try {
                    Files.createDirectories(stored.getParent());
                    Files.write(stored, data);
                } catch (IOException e) {
                    throw new ApiException(500, "Could not store '" + filename + "'. Please try again.");
                }
                Map<String, Object> row = new HashMap<>();
                row.put("org_id", orgId);
                row.put("filename", filename);
                row.put("pages", pages.size());
                row.put("size_bytes", (long) data.length);
                row.put("stored_path", stored.toString());
                long docId = new SimpleJdbcInsert(jdbc).withTableName("documents").usingGeneratedKeyColumns("id").executeAndReturnKey(row).longValue();
                List<Object[]> batch = new ArrayList<>();
                for (Chunker.Chunk c : chunks) batch.add(new Object[]{orgId, docId, c.page(), c.heading(), c.text()});
                jdbc.batchUpdate("INSERT INTO chunks(org_id, doc_id, page_no, heading, body) VALUES (?,?,?,?,?)", batch);
                return new UploadResult(filename, pages.size(), chunks.size(), replaced);
            });
            return result;
        } catch (RuntimeException e) {
            deleteQuietly(stored);                                           // roll the file back together with the DB rows
            if (e instanceof ApiException) throw e;
            throw new ApiException(500, "'" + filename + "' was read but could not be processed for search. Please try another file.");
        }
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------------------------------ list / delete / load
    public List<Map<String, Object>> listDocuments(long orgId) {
        return jdbc.query("SELECT id, filename, pages, size_bytes, created_at FROM documents WHERE org_id = ? ORDER BY id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("filename", rs.getString("filename"));
            m.put("pages", rs.getInt("pages"));
            m.put("size_kb", Math.round(rs.getLong("size_bytes") / 1024.0 * 10) / 10.0);
            m.put("uploaded_at", String.valueOf(rs.getTimestamp("created_at")));
            return m;
        }, orgId);
    }

    public void deleteDocument(long orgId, long docId) {
        List<String> paths = jdbc.queryForList("SELECT stored_path FROM documents WHERE id = ? AND org_id = ?", String.class, docId, orgId);
        if (paths.isEmpty()) throw new ApiException(404, "Document not found.");      // also what another org's document id looks like
        jdbc.update("DELETE FROM documents WHERE id = ? AND org_id = ?", docId, orgId);   // chunks cascade
        deleteQuietly(Path.of(paths.get(0)));
    }

    /** ONLY the calling organization's chunks ever leave the database. */
    public List<KbChunk> loadChunks(long orgId) {
        return jdbc.query(
            "SELECT c.id, c.doc_id, d.filename, c.page_no, c.heading, c.body FROM chunks c JOIN documents d ON d.id = c.doc_id WHERE c.org_id = ? AND d.org_id = ? ORDER BY c.id",
            (rs, i) -> new KbChunk(rs.getLong("id"), rs.getLong("doc_id"), rs.getString("filename"), rs.getInt("page_no"), rs.getString("heading"), rs.getString("body")),
            orgId, orgId);
    }
}
