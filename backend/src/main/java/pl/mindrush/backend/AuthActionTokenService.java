package pl.mindrush.backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@Transactional
public class AuthActionTokenService {

    private final AuthActionTokenRepository tokenRepository;
    private final Clock clock;

    public AuthActionTokenService(AuthActionTokenRepository tokenRepository, Clock clock) {
        this.tokenRepository = tokenRepository;
        this.clock = clock;
    }

    public TokenIssue issue(Long userId, AuthActionTokenType tokenType, Duration ttl) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId is required");
        }
        Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero()
                ? Duration.ofMinutes(30)
                : ttl;

        tokenRepository.deleteByUserIdAndTokenTypeAndConsumedAtIsNull(userId, tokenType);

        Instant now = clock.instant();
        Instant expiresAt = now.plus(safeTtl);
        String rawToken = SecureTokenUtils.randomUrlSafeToken(32);
        String tokenHash = SecureTokenUtils.sha256Hex(rawToken);
        tokenRepository.save(new AuthActionToken(userId, tokenType, tokenHash, now, expiresAt));
        return new TokenIssue(rawToken, expiresAt);
    }

    public Optional<AuthActionToken> consume(String rawToken, AuthActionTokenType tokenType) {
        String normalized = normalizeToken(rawToken);
        if (normalized == null) return Optional.empty();

        String tokenHash = SecureTokenUtils.sha256Hex(normalized);
        Optional<AuthActionToken> tokenOpt = tokenRepository.findByTokenHashAndTokenTypeAndConsumedAtIsNull(tokenHash, tokenType);
        if (tokenOpt.isEmpty()) return Optional.empty();

        AuthActionToken token = tokenOpt.get();
        Instant now = clock.instant();
        if (token.getExpiresAt().isBefore(now)) {
            token.setConsumedAt(now);
            tokenRepository.save(token);
            return Optional.empty();
        }

        token.setConsumedAt(now);
        tokenRepository.save(token);
        return Optional.of(token);
    }

    private static String normalizeToken(String rawToken) {
        if (rawToken == null) return null;
        String normalized = rawToken.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 400) return null;
        return normalized;
    }

    public record TokenIssue(String rawToken, Instant expiresAt) {}
}

