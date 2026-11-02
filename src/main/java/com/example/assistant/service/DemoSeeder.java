package com.example.assistant.service;

import com.example.assistant.AppProps;
import com.example.assistant.web.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Optional: start with --app.seed-demo=true to create the two sample organizations, upload their PDFs
 * (sample_data/pdfs/<org>/*.pdf) and write demo/embed_demo.html with both assistants embedded.
 */
@Component
@ConditionalOnProperty(name = "app.seed-demo", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    static final String PASSWORD = "Passw0rd!x";

    private final AuthService auth;
    private final KbService kb;
    private final AppProps props;

    public DemoSeeder(AuthService auth, KbService kb, AppProps props) {
        this.auth = auth;
        this.kb = kb;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Map<String, String[]> orgs = new LinkedHashMap<>();
        orgs.put("greenfield", new String[]{"Greenfield University", "admin@greenfield.example"});
        orgs.put("zenith", new String[]{"Zenith Electronics", "admin@zenith.example"});
        Map<String, String> tokens = new LinkedHashMap<>();
        for (var e : orgs.entrySet()) {
            String[] info = e.getValue();
            AuthService.Session s;
            try {
                s = auth.register(info[0], info[1], PASSWORD);
            } catch (ApiException ex) {
                if (ex.status() != 409) throw ex;
                s = auth.login(info[1], PASSWORD);
            }
            Path dir = Path.of("sample_data", "pdfs", e.getKey());
            try (Stream<Path> files = Files.list(dir)) {
                for (Path pdf : files.filter(p -> p.toString().endsWith(".pdf")).sorted().toList()) {
                    KbService.UploadResult r = kb.addDocument(s.orgId(), pdf.getFileName().toString(), Files.readAllBytes(pdf));
                    log.info("[{}] {} -> {} pages, {} sections", info[0], r.filename(), r.pages(), r.chunks());
                }
            }
            tokens.put(e.getKey(), auth.assistantTokenOf(s.orgId()));
            log.info("[{}] assistant URL: {}/a/{}", info[0], props.baseUrl(), tokens.get(e.getKey()));
        }
        Path template = Path.of("demo", "embed_demo.template.html");
        if (Files.exists(template)) {
            String page = Files.readString(template, StandardCharsets.UTF_8)
                .replace("{{BASE}}", props.baseUrl())
                .replace("{{TOKEN_A}}", tokens.get("greenfield"))
                .replace("{{TOKEN_B}}", tokens.get("zenith"));
            Files.writeString(Path.of("demo", "embed_demo.html"), page, StandardCharsets.UTF_8);
            log.info("Wrote demo/embed_demo.html - open it in a browser.");
        }
        log.info("Dashboard logins: admin@greenfield.example / admin@zenith.example   password: {}", PASSWORD);
    }
}
