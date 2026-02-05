package pl.mindrush.backend.guest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.mindrush.backend.JwtCookieAuthenticationFilter;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/guest/session")
public class GuestSessionController {

    private final GuestSessionService service;

    public GuestSessionController(GuestSessionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> ensureSession(HttpServletRequest request, Authentication authentication) {
        String preferred = displayNameFrom(authentication);
        Long userId = userIdFrom(authentication);
        GuestSessionService.Result result = service.ensureSession(request, preferred, userId);
        GuestSession session = result.session();

        return ResponseEntity
                .status(201)
                .header(result.headerName(), result.headerValue())
                .body(Map.of(
                        "status", "OK",
                        "expiresAt", session.getExpiresAt().toString(),
                        "serverTime", Instant.now().toString()
                ));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSession(HttpServletRequest request) {
        GuestSession session = service.requireValidSession(request);
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "displayName", session.getDisplayName(),
                "expiresAt", session.getExpiresAt().toString(),
                "rankPoints", session.getRankPoints(),
                "xp", session.getXp(),
                "coins", session.getCoins()
        ));
    }

    @DeleteMapping
    public ResponseEntity<Void> clearSession(HttpServletRequest request) {
        service.revokeSessionIfPresent(request);
        return ResponseEntity
                .noContent()
                .header("Set-Cookie", service.clearCookieHeader())
                .build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(HttpServletRequest request, Authentication authentication) {
        String preferred = displayNameFrom(authentication);
        Long userId = userIdFrom(authentication);
        service.heartbeat(request, preferred, userId);
        return ResponseEntity.noContent().build();
    }

    private static String displayNameFrom(Authentication authentication) {
        if (authentication == null) return null;
        Object p = authentication.getPrincipal();
        if (p instanceof JwtCookieAuthenticationFilter.AuthenticatedUser au) {
            String d = au.displayName();
            return d == null || d.isBlank() ? null : d.trim();
        }
        return null;
    }

    private static Long userIdFrom(Authentication authentication) {
        if (authentication == null) return null;
        Object p = authentication.getPrincipal();
        if (p instanceof JwtCookieAuthenticationFilter.AuthenticatedUser au) {
            return au.id();
        }
        return null;
    }
}
