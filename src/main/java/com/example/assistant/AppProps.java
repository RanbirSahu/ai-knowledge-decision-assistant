package com.example.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from the "app.*" properties in application.properties (which read ANTHROPIC_API_KEY etc. from env or .env). */
@ConfigurationProperties(prefix = "app")
public record AppProps(String anthropicApiKey, String llmModel, String publicBaseUrl, String dataDir, boolean seedDemo) {
    public String baseUrl() {
        String b = publicBaseUrl == null ? "" : publicBaseUrl;
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
