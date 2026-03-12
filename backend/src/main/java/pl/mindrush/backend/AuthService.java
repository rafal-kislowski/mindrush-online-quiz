package pl.mindrush.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.config.AppAuthProperties;
import pl.mindrush.backend.config.AppMailProperties;
import pl.mindrush.backend.mail.AuthMailWorkflowService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
@Transactional
public class AuthService {
    private static final int DISPLAY_NAME_CHANGE_COST_COINS = 50_000;
    private static final Duration DISPLAY_NAME_CHANGE_COOLDOWN = Duration.ofDays(7);

    private final Clock clock;
    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthCookies cookies;
    private final Duration refreshTtl;
    private final AppAuthProperties authProperties;
    private final AppMailProperties mailProperties;
    private final AuthActionTokenService actionTokenService;
    private final AuthMailWorkflowService authMailWorkflowService;

    public AuthService(
            Clock clock,
            AppUserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthCookies cookies,
            @Value("${app.jwt.refresh-ttl:P14D}") Duration refreshTtl,
            AppAuthProperties authProperties,
            AppMailProperties mailProperties,
            AuthActionTokenService actionTokenService,
            AuthMailWorkflowService authMailWorkflowService
    ) {
        this.clock = clock;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.cookies = cookies;
        this.refreshTtl = refreshTtl;
        this.authProperties = authProperties;
        this.mailProperties = mailProperties;
        this.actionTokenService = actionTokenService;
        this.authMailWorkflowService = authMailWorkflowService;
    }

    public AuthResult register(String email, String password, String displayName) {
        String normalized = normalizeEmail(email);
        if (userRepository.existsByEmailIgnoreCase(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Email already registered");
        }

        Instant now = clock.instant();
        AppUser user = new AppUser(
                normalized,
                passwordEncoder.encode(password),
                normalizeDisplayName(displayName),
                Set.of(AppRole.USER),
                now
        );
        user.setEmailVerified(false);
        user.setEmailVerifiedAt(null);
        user = userRepository.save(user);

        sendVerificationEmail(user, true);

        if (authProperties.isRequireVerifiedEmail()) {
            return new AuthResult(
                    toAuthUserDto(user),
                    new ResponseCookies(cookies.clearAccessCookie(), cookies.clearRefreshCookie())
            );
        }
        return issueTokens(user);
    }

    public AuthResult login(String email, String password) {
        String normalized = normalizeEmail(email);
        AppUser user = userRepository.findByEmailIgnoreCase(normalized)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid credentials"));
        ensureNotBanned(user);
        ensureVerifiedForLogin(user);

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid credentials");
        }

        return issueTokens(user);
    }

    public AuthResult refresh(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Missing refresh token");
        }

        String tokenHash = SecureTokenUtils.sha256Hex(refreshTokenValue);
        RefreshToken token = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid refresh token"));

        Instant now = clock.instant();
        if (token.getExpiresAt().isBefore(now)) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            throw new ResponseStatusException(UNAUTHORIZED, "Refresh token expired");
        }

        AppUser user = token.getUser();
        if (user != null && user.getRoles().contains(AppRole.BANNED)) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            throw new ResponseStatusException(UNAUTHORIZED, "Account is banned");
        }
        ensureVerifiedForLogin(user);
        JwtService.Token access = jwtService.createAccessToken(user);

        Duration refreshRemaining = Duration.between(now, token.getExpiresAt());
        if (refreshRemaining.isNegative()) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            throw new ResponseStatusException(UNAUTHORIZED, "Refresh token expired");
        }

        ResponseCookie accessCookie = cookies.accessCookie(access.value(), Duration.between(now, access.expiresAt()));
        ResponseCookie refreshCookie = cookies.refreshCookie(refreshTokenValue, refreshRemaining);

        AuthUserDto dto = toAuthUserDto(user);
        return new AuthResult(dto, new ResponseCookies(accessCookie, refreshCookie));
    }

    public void logout(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) return;
        String tokenHash = SecureTokenUtils.sha256Hex(refreshTokenValue);
        refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash).ifPresent(t -> {
            t.setRevoked(true);
            refreshTokenRepository.save(t);
        });
    }

    public ResponseCookies clearCookies() {
        return new ResponseCookies(cookies.clearAccessCookie(), cookies.clearRefreshCookie());
    }

    public void resendVerificationEmail(String email) {
        AppUser user = userRepository.findByEmailIgnoreCase(normalizeEmail(email)).orElse(null);
        if (user == null) return;
        if (user.isEmailVerified()) return;
        if (user.getRoles().contains(AppRole.BANNED)) return;
        sendVerificationEmail(user, false);
    }

    public void requestPasswordReset(String email) {
        AppUser user = userRepository.findByEmailIgnoreCase(normalizeEmail(email)).orElse(null);
        if (user == null) return;
        if (user.getRoles().contains(AppRole.BANNED)) return;
        Instant now = clock.instant();
        if (!canSendPasswordResetEmail(user, now)) return;

        AuthActionTokenService.TokenIssue token = actionTokenService.issue(
                user.getId(),
                AuthActionTokenType.PASSWORD_RESET,
                mailProperties.getResetTtl()
        );
        authMailWorkflowService.sendPasswordReset(user, token.rawToken(), token.expiresAt());
        user.setLastPasswordResetEmailSentAt(now);
        userRepository.save(user);
    }

    public void resetPassword(String rawToken, String newPassword) {
        String normalizedPassword = normalizePassword(newPassword);
        AuthActionToken actionToken = actionTokenService.consume(rawToken, AuthActionTokenType.PASSWORD_RESET)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Reset token is invalid or expired"));

        AppUser user = userRepository.findById(actionToken.getUserId())
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Reset token is invalid or expired"));
        ensureNotBanned(user);

        user.setPasswordHash(passwordEncoder.encode(normalizedPassword));
        userRepository.save(user);
        refreshTokenRepository.deleteAllByUser_Id(user.getId());
    }

    public void verifyEmail(String rawToken) {
        AuthActionToken actionToken = actionTokenService.consume(rawToken, AuthActionTokenType.EMAIL_VERIFY)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Verification token is invalid or expired"));

        AppUser user = userRepository.findById(actionToken.getUserId())
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Verification token is invalid or expired"));

        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(clock.instant());
            userRepository.save(user);
        }
    }

    public AuthUserDto updateDisplayName(long userId, String displayName) {
        AppUser user = findActiveUserById(userId);
        String nextDisplayName = normalizeDisplayName(displayName);
        String currentDisplayName = String.valueOf(user.getDisplayName() == null ? "" : user.getDisplayName()).trim();
        if (nextDisplayName.equalsIgnoreCase(currentDisplayName)) {
            throw new ResponseStatusException(BAD_REQUEST, "New nickname must be different from current nickname");
        }

        Instant now = clock.instant();
        Instant lastChangeAt = user.getLastDisplayNameChangeAt();
        if (lastChangeAt != null) {
            Instant nextAllowedAt = lastChangeAt.plus(DISPLAY_NAME_CHANGE_COOLDOWN);
            if (nextAllowedAt.isAfter(now)) {
                throw new ResponseStatusException(
                        BAD_REQUEST,
                        "Nickname can be changed once every 7 days. Next change available on " + nextAllowedAt
                );
            }
        }

        if (user.getCoins() < DISPLAY_NAME_CHANGE_COST_COINS) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Not enough coins. Nickname change costs " + DISPLAY_NAME_CHANGE_COST_COINS + " coins"
            );
        }

        user.setCoins(user.getCoins() - DISPLAY_NAME_CHANGE_COST_COINS);
        user.setDisplayName(nextDisplayName);
        user.setLastDisplayNameChangeAt(now);
        userRepository.save(user);
        return toAuthUserDto(user);
    }

    public AuthResult changePassword(long userId, String currentPassword, String newPassword) {
        AppUser user = findActiveUserById(userId);
        String current = currentPassword == null ? "" : currentPassword;
        if (!passwordEncoder.matches(current, user.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Current password is incorrect");
        }

        String normalizedPassword = normalizePassword(newPassword);
        if (passwordEncoder.matches(normalizedPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(BAD_REQUEST, "New password must be different from current password");
        }

        user.setPasswordHash(passwordEncoder.encode(normalizedPassword));
        userRepository.save(user);
        refreshTokenRepository.deleteAllByUser_Id(user.getId());
        return issueTokens(user);
    }

    public void revokeAllSessions(long userId) {
        AppUser user = findActiveUserById(userId);
        refreshTokenRepository.deleteAllByUser_Id(user.getId());
    }

    private AuthResult issueTokens(AppUser user) {
        ensureNotBanned(user);
        ensureVerifiedForLogin(user);

        JwtService.Token access = jwtService.createAccessToken(user);

        Instant now = clock.instant();
        user.setLastLoginAt(now);
        userRepository.save(user);
        Instant refreshExpiresAt = now.plus(refreshTtl);
        String refreshValue = SecureTokenUtils.randomUrlSafeToken(32);
        RefreshToken refresh = new RefreshToken(user, SecureTokenUtils.sha256Hex(refreshValue), now, refreshExpiresAt);
        refreshTokenRepository.save(refresh);

        ResponseCookie accessCookie = cookies.accessCookie(access.value(), Duration.between(now, access.expiresAt()));
        ResponseCookie refreshCookie = cookies.refreshCookie(refreshValue, refreshTtl);

        AuthUserDto dto = toAuthUserDto(user);
        return new AuthResult(dto, new ResponseCookies(accessCookie, refreshCookie));
    }

    private static String normalizeEmail(String email) {
        String e = email == null ? "" : email.trim();
        return e.toLowerCase();
    }

    private static String normalizeDisplayName(String displayName) {
        String d = displayName == null ? "" : displayName.trim();
        if (d.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "Nickname is required");
        if (d.length() < 3 || d.length() > 32) throw new ResponseStatusException(BAD_REQUEST, "Nickname must be 3-32 characters");
        // Allowed: letters, digits, space, dash, underscore
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            boolean ok =
                    (c >= 'a' && c <= 'z') ||
                    (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') ||
                    c == ' ' || c == '-' || c == '_';
            if (!ok) throw new ResponseStatusException(BAD_REQUEST, "Nickname contains invalid characters");
        }
        return d;
    }

    private static void ensureNotBanned(AppUser user) {
        if (user != null && user.getRoles().contains(AppRole.BANNED)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Account is banned");
        }
    }

    private AppUser findActiveUserById(long userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Authentication is required"));
        ensureNotBanned(user);
        return user;
    }

    private void ensureVerifiedForLogin(AppUser user) {
        if (user == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid credentials");
        }
        if (authProperties.isRequireVerifiedEmail() && !user.isEmailVerified()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Please verify your email before signing in");
        }
    }

    private static String normalizePassword(String password) {
        String p = password == null ? "" : password;
        if (p.length() < 8 || p.length() > 72) {
            throw new ResponseStatusException(BAD_REQUEST, "Password must be 8-72 characters");
        }
        return p;
    }

    private AuthUserDto toAuthUserDto(AppUser user) {
        String displayName = user.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = user.getEmail() == null ? "Player" : user.getEmail().split("@", 2)[0];
        }
        return new AuthUserDto(
                user.getId(),
                user.getEmail(),
                displayName,
                user.getRoles().stream().map(Enum::name).sorted().toList(),
                user.getRankPoints(),
                user.getXp(),
                user.getCoins(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.getLastDisplayNameChangeAt()
        );
    }

    private void sendVerificationEmail(AppUser user, boolean force) {
        Instant now = clock.instant();
        if (!force && !canSendVerificationEmail(user, now)) {
            return;
        }

        AuthActionTokenService.TokenIssue token = actionTokenService.issue(
                user.getId(),
                AuthActionTokenType.EMAIL_VERIFY,
                mailProperties.getVerifyTtl()
        );
        authMailWorkflowService.sendEmailVerification(user, token.rawToken(), token.expiresAt());
        user.setLastVerificationEmailSentAt(now);
        userRepository.save(user);
    }

    private boolean canSendVerificationEmail(AppUser user, Instant now) {
        Duration cooldown = mailProperties.getVerifyResendCooldown();
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            return true;
        }
        Instant lastSent = user.getLastVerificationEmailSentAt();
        if (lastSent == null) return true;
        Duration sinceLast = Duration.between(lastSent, now);
        return !sinceLast.isNegative() && sinceLast.compareTo(cooldown) >= 0;
    }

    private boolean canSendPasswordResetEmail(AppUser user, Instant now) {
        Duration cooldown = mailProperties.getResetRequestCooldown();
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            return true;
        }
        Instant lastSent = user.getLastPasswordResetEmailSentAt();
        if (lastSent == null) return true;
        Duration sinceLast = Duration.between(lastSent, now);
        return !sinceLast.isNegative() && sinceLast.compareTo(cooldown) >= 0;
    }

    public record AuthUserDto(
            Long id,
            String email,
            String displayName,
            java.util.List<String> roles,
            int rankPoints,
            int xp,
            int coins,
            boolean emailVerified,
            Instant createdAt,
            Instant lastLoginAt,
            Instant lastDisplayNameChangeAt
    ) {}

    public record AuthResult(AuthUserDto user, ResponseCookies cookies) {}

    public record ResponseCookies(ResponseCookie access, ResponseCookie refresh) {}
}
