package pl.mindrush.backend.guest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<Map<String, Object>> ensureSession(HttpServletRequest request) {
        GuestSessionService.Result result = service.ensureSession(request);
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

    @DeleteMapping
    public ResponseEntity<Void> clearSession(HttpServletRequest request) {
        service.revokeSessionIfPresent(request);
        return ResponseEntity
                .noContent()
                .header("Set-Cookie", service.clearCookieHeader())
                .build();
    }
}
