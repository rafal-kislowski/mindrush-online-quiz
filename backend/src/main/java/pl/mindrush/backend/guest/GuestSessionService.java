package pl.mindrush.backend.guest;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
@Transactional
public class GuestSessionService {

    private final GuestSessionRepository repository;
    private final Duration ttl;
    private final String cookieName;
    private final boolean secureCookie;

    private final SecureRandom random = new SecureRandom();

    private static final String[] ADJECTIVES = {
            "Brave", "Calm", "Clever", "Cosmic", "Curious", "Daring", "Eager", "Epic", "Fast", "Friendly",
            "Gentle", "Happy", "Icy", "Jolly", "Kind", "Lucky", "Mighty", "Nimble", "Quiet", "Rapid",
            "Shiny", "Silent", "Smart", "Sunny", "Swift", "Tiny", "Witty", "Zany"
    };

    private static final String[] NOUNS = {
            "Panda", "Fox", "Otter", "Hawk", "Tiger", "Falcon", "Koala", "Dolphin", "Badger", "Lynx",
            "Raven", "Wolf", "Turtle", "Penguin", "Sparrow", "Giraffe", "Cobra", "Eagle", "Orca", "Bison",
            "Cheetah", "Dragon", "Phoenix", "Comet", "Nimbus", "Rocket"
    };

    public GuestSessionService(
            GuestSessionRepository repository,
            @Value("${guest.session.ttl:PT24H}") Duration ttl,
            @Value("${guest.session.cookie-name:guestSessionId}") String cookieName,
            @Value("${app.cookies.secure:false}") boolean secureCookie
    ) {
        this.repository = repository;
        this.ttl = ttl;
        this.cookieName = cookieName;
        this.secureCookie = secureCookie;
    }

    public Result ensureSession(HttpServletRequest request) {
        Instant now = Instant.now();
        String sessionId = readCookie(request, cookieName).orElse(null);
        GuestSession session = null;

        if (sessionId != null) {
            session = repository.findById(sessionId)
                    .filter(s -> !s.isRevoked())
                    .filter(s -> s.getExpiresAt().isAfter(now))
                    .orElse(null);
        }

        if (session == null) {
            session = GuestSession.createNew(now, now.plus(ttl));
        } else {
            session.setLastSeenAt(now);
            session.setExpiresAt(now.plus(ttl));
        }

        if (session.getDisplayName() == null || session.getDisplayName().isBlank()) {
            session.setDisplayName(generateGuestDisplayName());
        }

        repository.save(session);

        ResponseCookie cookie = ResponseCookie.from(cookieName, session.getId())
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(ttl)
                .build();

        return new Result(session, HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public Optional<GuestSession> findValidSession(HttpServletRequest request) {
        Instant now = Instant.now();
        return readCookie(request, cookieName)
                .flatMap(repository::findById)
                .filter(s -> !s.isRevoked())
                .filter(s -> s.getExpiresAt().isAfter(now));
    }

    public GuestSession requireValidSession(HttpServletRequest request) {
        return findValidSession(request).orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Guest session is missing or expired"));
    }

    public String clearCookieHeader() {
        ResponseCookie cookie = ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        return cookie.toString();
    }

    public void revokeSessionIfPresent(HttpServletRequest request) {
        Instant now = Instant.now();
        readCookie(request, cookieName)
                .flatMap(repository::findById)
                .ifPresent(session -> {
                    session.setRevoked(true);
                    session.setLastSeenAt(now);
                    repository.save(session);
                });
    }

    public void updateDisplayName(HttpServletRequest request, String displayName) {
        Instant now = Instant.now();
        GuestSession session = requireValidSession(request);
        session.setDisplayName(displayName);
        session.setLastSeenAt(now);
        repository.save(session);
    }

    public void heartbeat(HttpServletRequest request) {
        Instant now = Instant.now();
        GuestSession session = requireValidSession(request);
        session.setLastSeenAt(now);
        session.setExpiresAt(now.plus(ttl));
        repository.save(session);
    }

    private String generateGuestDisplayName() {
        String adjective = ADJECTIVES[random.nextInt(ADJECTIVES.length)];
        String noun = NOUNS[random.nextInt(NOUNS.length)];
        int suffix = random.nextInt(1000);
        return String.format("%s%s-%03d", adjective, noun, suffix);
    }

    private static Optional<String> readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public record Result(GuestSession session, String headerName, String headerValue) {
    }
}
