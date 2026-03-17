package pl.mindrush.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private static final Base64.Encoder B64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_URL_DEC = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final byte[] secretBytes;
    private final Duration accessTtl;

    public JwtService(
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-ttl:PT15M}") Duration accessTtl
    ) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.secretBytes = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        this.accessTtl = accessTtl;
    }

    public Token createAccessToken(AppUser user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(accessTtl);
        Instant sessionStartedAt = user != null && user.getLastLoginAt() != null
                ? user.getLastLoginAt()
                : now;

        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new HashMap<>();
        payload.put("sub", String.valueOf(user.getId()));
        payload.put("email", user.getEmail());
        payload.put("roles", user.getRoles().stream().map(Enum::name).toList());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        payload.put("sid", sessionStartedAt.toEpochMilli());

        String token = sign(header, payload);
        return new Token(token, expiresAt);
    }

    public JwtPayload parseAndValidateAccessToken(String token) {
        if (token == null || token.isBlank()) throw new UnauthorizedException("Missing token");

        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new UnauthorizedException("Invalid token");

        String unsigned = parts[0] + "." + parts[1];
        byte[] actualSig = B64_URL_DEC.decode(parts[2]);
        byte[] expectedSig = hmacSha256(unsigned.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(actualSig, expectedSig)) throw new UnauthorizedException("Invalid token");

        Map<String, Object> payload = decodeJson(parts[1]);
        String sub = asString(payload.get("sub"));
        String email = asString(payload.get("email"));
        long exp = asLong(payload.get("exp"));
        Long sessionStartedAtEpochMillis = asLongOrNull(payload.get("sid"));
        Instant expiresAt = Instant.ofEpochSecond(exp);
        if (expiresAt.isBefore(clock.instant())) throw new UnauthorizedException("Token expired");

        List<String> roles = asStringList(payload.get("roles"));
        long userId;
        try {
            userId = Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("Invalid token subject");
        }

        return new JwtPayload(
                userId,
                email,
                roles == null ? Set.of() : roles.stream().collect(Collectors.toUnmodifiableSet()),
                expiresAt,
                sessionStartedAtEpochMillis
        );
    }

    private String sign(Map<String, Object> header, Map<String, Object> payload) {
        if (secretBytes.length == 0) throw new IllegalStateException("app.jwt.secret is not configured");

        try {
            String headerJson = objectMapper.writeValueAsString(header);
            String payloadJson = objectMapper.writeValueAsString(payload);

            String headerPart = B64_URL.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadPart = B64_URL.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String unsigned = headerPart + "." + payloadPart;
            String signaturePart = B64_URL.encodeToString(hmacSha256(unsigned.getBytes(StandardCharsets.UTF_8)));
            return unsigned + "." + signaturePart;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign token", e);
        }
    }

    private byte[] hmacSha256(byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC error", e);
        }
    }

    private Map<String, Object> decodeJson(String base64Url) {
        try {
            byte[] jsonBytes = B64_URL_DEC.decode(base64Url);
            return objectMapper.readValue(jsonBytes, new TypeReference<>() {});
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid token");
        }
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid token");
        }
    }

    private static Long asLongOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid token");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object v) {
        if (v == null) return null;
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return null;
    }

    public record Token(String value, Instant expiresAt) {}

    public record JwtPayload(
            long userId,
            String email,
            Set<String> roles,
            Instant expiresAt,
            Long sessionStartedAtEpochMillis
    ) {}

    public static final class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }
}
