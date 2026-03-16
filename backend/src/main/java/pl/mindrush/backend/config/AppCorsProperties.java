package pl.mindrush.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.cors")
public class AppCorsProperties {

    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:4200",
            "http://127.0.0.1:4200"
    ));

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }

    public List<String> normalizedAllowedOrigins() {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String origin : allowedOrigins) {
            if (origin == null) continue;
            String trimmed = origin.trim();
            if (!trimmed.isEmpty()) unique.add(trimmed);
        }
        if (unique.isEmpty()) {
            unique.add("http://localhost:4200");
            unique.add("http://127.0.0.1:4200");
        }
        return List.copyOf(unique);
    }
}
