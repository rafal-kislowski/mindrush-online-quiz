package pl.mindrush.backend.mail;

import org.springframework.stereotype.Service;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.config.AppMailProperties;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class AuthMailWorkflowService {

    private final TransactionalMailService mailService;
    private final AppMailProperties mailProperties;
    private final Clock clock;

    public AuthMailWorkflowService(
            TransactionalMailService mailService,
            AppMailProperties mailProperties,
            Clock clock
    ) {
        this.mailService = mailService;
        this.mailProperties = mailProperties;
        this.clock = clock;
    }

    public void sendEmailVerification(AppUser user, String rawToken, Instant expiresAt) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;

        String url = buildActionUrl(mailProperties.getVerifyPath(), rawToken);
        long ttlMinutes = ttlMinutesUntil(expiresAt, clock.instant());
        mailService.sendTemplate(
                user.getEmail(),
                "MindRush - verify your account",
                "mail/verify-account",
                Map.of(
                        "displayName", displayNameFor(user),
                        "actionUrl", url,
                        "ttlMinutes", ttlMinutes,
                        "supportEmail", safe(mailProperties.getSupportEmail())
                )
        );
    }

    public void sendPasswordReset(AppUser user, String rawToken, Instant expiresAt) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;

        String url = buildActionUrl(mailProperties.getResetPath(), rawToken);
        long ttlMinutes = ttlMinutesUntil(expiresAt, clock.instant());
        mailService.sendTemplate(
                user.getEmail(),
                "MindRush - reset your password",
                "mail/reset-password",
                Map.of(
                        "displayName", displayNameFor(user),
                        "actionUrl", url,
                        "ttlMinutes", ttlMinutes,
                        "supportEmail", safe(mailProperties.getSupportEmail())
                )
        );
    }

    private String buildActionUrl(String path, String token) {
        String base = trimTrailingSlash(normalizeBaseUrl(safe(mailProperties.getFrontendBaseUrl())));
        String safePath = normalizePath(path);
        String encodedToken = URLEncoder.encode(safe(token), StandardCharsets.UTF_8);
        return base + safePath + "?token=" + encodedToken;
    }

    private static String normalizePath(String path) {
        String raw = safe(path).trim();
        if (raw.isEmpty()) return "/";
        return raw.startsWith("/") ? raw : "/" + raw;
    }

    private static long ttlMinutesUntil(Instant expiresAt, Instant now) {
        if (expiresAt == null) return 0L;
        Duration remaining = Duration.between(now, expiresAt);
        long min = remaining.toMinutes();
        return Math.max(0L, min);
    }

    private static String displayNameFor(AppUser user) {
        String displayName = user.getDisplayName() == null ? "" : user.getDisplayName().trim();
        if (!displayName.isEmpty()) return displayName;
        String email = safe(user.getEmail());
        int at = email.indexOf('@');
        if (at > 0) return email.substring(0, at);
        return "Player";
    }

    private static String trimTrailingSlash(String value) {
        String normalized = safe(value).trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? "http://localhost:4200" : normalized;
    }

    private static String normalizeBaseUrl(String rawBaseUrl) {
        String value = safe(rawBaseUrl).trim();
        if (value.isEmpty()) return "http://localhost:4200";
        if (value.contains("://")) return value;

        String lower = value.toLowerCase();
        if (lower.startsWith("localhost") || lower.startsWith("127.0.0.1")) {
            return "http://" + value;
        }
        return "https://" + value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
