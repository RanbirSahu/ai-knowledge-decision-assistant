package com.example.assistant.service;

import com.example.assistant.web.ApiException;
import com.example.assistant.web.OrgPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;

/**
 * Organization authentication. Two completely separate credential types:
 *  - admin session token  "adm_..."  stored HASHED in `sessions`, sent as "Authorization: Bearer ..."
 *  - assistant token      "ast_..."  stored in `orgs`, accepted ONLY by the public ask endpoints
 * An assistant token is never looked up in `sessions`, so it can never open the dashboard APIs.
 */
@Service
public class AuthService {
    private static final long SESSION_TTL_MS = 24L * 3600 * 1000;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;

    public AuthService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Session(String token, String orgName, long orgId) {}
    public record OrgRef(long id, String name) {}

    // ------------------------------------------------------------------------------------------ passwords & tokens
    private String hash(String password, byte[] salt) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
            return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("password hashing failed", e);
        }
    }

    private String randomToken(String prefix, int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Random and unguessable (192 bits). Carries no information about the org, credentials or documents. */
    public String newAssistantToken() { return randomToken("ast_", 24); }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String createSession(long orgId) {
        String token = randomToken("adm_", 32);
        jdbc.update("INSERT INTO sessions(token_hash, org_id, expires_at) VALUES (?,?,?)", sha256(token), orgId, System.currentTimeMillis() + SESSION_TTL_MS);
        return token;
    }

    // ------------------------------------------------------------------------------------------ register / login / logout
    public Session register(String orgName, String email, String password) {
        String name = orgName == null ? "" : orgName.strip();
        String mail = email == null ? "" : email.strip().toLowerCase();
        if (name.length() < 2) throw new ApiException(400, "Organization name must be at least 2 characters.");
        if (!EMAIL.matcher(mail).matches()) throw new ApiException(400, "Please enter a valid email address.");
        if (password == null || password.length() < 8) throw new ApiException(400, "Password must be at least 8 characters.");
        if (!jdbc.queryForList("SELECT 1 FROM orgs WHERE email = ?", mail).isEmpty()) throw new ApiException(409, "An account with this email already exists.");

        byte[] salt = new byte[16];
        random.nextBytes(salt);
        Map<String, Object> row = new HashMap<>();
        row.put("name", name);
        row.put("email", mail);
        row.put("pw_hash", hash(password, salt));
        row.put("pw_salt", HexFormat.of().formatHex(salt));
        row.put("assistant_token", newAssistantToken());
        long orgId;
        try {
            orgId = new SimpleJdbcInsert(jdbc).withTableName("orgs").usingGeneratedKeyColumns("id").executeAndReturnKey(row).longValue();
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "An account with this email already exists.");
        }
        jdbc.update("INSERT INTO org_usage(org_id) VALUES (?)", orgId);
        return new Session(createSession(orgId), name, orgId);
    }

    public Session login(String email, String password) {
        String mail = email == null ? "" : email.strip().toLowerCase();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, pw_hash, pw_salt FROM orgs WHERE email = ?", mail);
        if (rows.isEmpty() || password == null) throw new ApiException(401, "Incorrect email or password.");
        Map<String, Object> r = rows.get(0);
        byte[] salt = HexFormat.of().parseHex((String) r.get("pw_salt"));
        boolean ok = MessageDigest.isEqual(hash(password, salt).getBytes(StandardCharsets.UTF_8), ((String) r.get("pw_hash")).getBytes(StandardCharsets.UTF_8));
        if (!ok) throw new ApiException(401, "Incorrect email or password.");
        long id = ((Number) r.get("id")).longValue();
        return new Session(createSession(id), (String) r.get("name"), id);
    }

    public void logout(String authHeader) {
        jdbc.update("DELETE FROM sessions WHERE token_hash = ?", sha256(bearer(authHeader)));
    }

    private static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) throw new ApiException(401, "Authentication required. Please log in.");
        return header.substring(7).strip();
    }

    // ------------------------------------------------------------------------------------------ admin session check
    public OrgPrincipal requireOrg(String authHeader) {
        String token = bearer(authHeader);
        if (!token.startsWith("adm_")) throw new ApiException(401, "Invalid credentials for the admin API.");
        List<OrgPrincipal> rows = jdbc.query(
            "SELECT o.id, o.name, o.email, o.assistant_token, s.expires_at FROM sessions s JOIN orgs o ON o.id = s.org_id WHERE s.token_hash = ?",
            (rs, i) -> rs.getLong("expires_at") < System.currentTimeMillis() ? null
                    : new OrgPrincipal(rs.getLong("id"), rs.getString("name"), rs.getString("email"), rs.getString("assistant_token")),
            sha256(token));
        if (rows.isEmpty() || rows.get(0) == null) throw new ApiException(401, "Session expired or invalid. Please log in again.");
        return rows.get(0);
    }

    // ------------------------------------------------------------------------------------------ assistant tokens
    /** Validates a PUBLIC assistant token server-side and identifies the tenant. */
    public OrgRef orgByAssistantToken(String token) {
        if (token == null || !token.startsWith("ast_")) throw new ApiException(401, "Invalid assistant token.");
        List<OrgRef> rows = jdbc.query("SELECT id, name FROM orgs WHERE assistant_token = ?", (rs, i) -> new OrgRef(rs.getLong("id"), rs.getString("name")), token);
        if (rows.isEmpty()) throw new ApiException(401, "Invalid or expired assistant token.");
        return rows.get(0);
    }

    /** New token; the old URL stops working immediately. */
    public String regenerateAssistantToken(long orgId) {
        String t = newAssistantToken();
        jdbc.update("UPDATE orgs SET assistant_token = ? WHERE id = ?", t, orgId);
        return t;
    }

    public String assistantTokenOf(long orgId) {
        return jdbc.queryForObject("SELECT assistant_token FROM orgs WHERE id = ?", String.class, orgId);
    }
}
