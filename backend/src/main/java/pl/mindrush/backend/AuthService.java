package pl.mindrush.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
@Transactional
public class AuthService {

    private static final SecureRandom RNG = new SecureRandom();

    private final Clock clock;
    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthCookies cookies;
    private final Duration refreshTtl;

    public AuthService(
            Clock clock,
            AppUserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthCookies cookies,
            @Value("${app.jwt.refresh-ttl:P14D}") Duration refreshTtl
    ) {
        this.clock = clock;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.cookies = cookies;
        this.refreshTtl = refreshTtl;
    }

    public AuthResult register(String email, String password) {
        String normalized = normalizeEmail(email);
        if (userRepository.existsByEmailIgnoreCase(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Email already registered");
        }

        Instant now = clock.instant();
        AppUser user = new AppUser(
                normalized,
                passwordEncoder.encode(password),
                Set.of(AppRole.USER),
                now
        );
        user = userRepository.save(user);
        return issueTokens(user);
    }

    public AuthResult login(String email, String password) {
        String normalized = normalizeEmail(email);
        AppUser user = userRepository.findByEmailIgnoreCase(normalized)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid credentials");
        }

        return issueTokens(user);
    }

    public AuthResult refresh(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Missing refresh token");
        }

        String tokenHash = sha256Hex(refreshTokenValue);
        RefreshToken token = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid refresh token"));

        Instant now = clock.instant();
        if (token.getExpiresAt().isBefore(now)) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            throw new ResponseStatusException(UNAUTHORIZED, "Refresh token expired");
        }

        AppUser user = token.getUser();

        token.setRevoked(true);
        refreshTokenRepository.save(token);

        return issueTokens(user);
    }

    public void logout(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) return;
        String tokenHash = sha256Hex(refreshTokenValue);
        refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash).ifPresent(t -> {
            t.setRevoked(true);
            refreshTokenRepository.save(t);
        });
    }

    public ResponseCookies clearCookies() {
        return new ResponseCookies(cookies.clearAccessCookie(), cookies.clearRefreshCookie());
    }

    private AuthResult issueTokens(AppUser user) {
        JwtService.Token access = jwtService.createAccessToken(user);

        Instant now = clock.instant();
        Instant refreshExpiresAt = now.plus(refreshTtl);
        String refreshValue = randomToken();
        RefreshToken refresh = new RefreshToken(user, sha256Hex(refreshValue), now, refreshExpiresAt);
        refreshTokenRepository.save(refresh);

        ResponseCookie accessCookie = cookies.accessCookie(access.value(), Duration.between(now, access.expiresAt()));
        ResponseCookie refreshCookie = cookies.refreshCookie(refreshValue, refreshTtl);

        AuthUserDto dto = new AuthUserDto(
                user.getId(),
                user.getEmail(),
                user.getRoles().stream().map(Enum::name).sorted().toList()
        );
        return new AuthResult(dto, new ResponseCookies(accessCookie, refreshCookie));
    }

    private static String normalizeEmail(String email) {
        String e = email == null ? "" : email.trim();
        return e.toLowerCase();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 error", e);
        }
    }

    public record AuthUserDto(Long id, String email, java.util.List<String> roles) {}

    public record AuthResult(AuthUserDto user, ResponseCookies cookies) {}

    public record ResponseCookies(ResponseCookie access, ResponseCookie refresh) {}
}

