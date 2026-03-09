package pl.mindrush.backend.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.mindrush.backend.JwtCookieAuthenticationFilter;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Validated
@RestController
@RequestMapping("/api/notifications")
public class UserNotificationController {

    private final UserNotificationService notificationService;
    private final UserNotificationStreamService streamService;

    public UserNotificationController(
            UserNotificationService notificationService,
            UserNotificationStreamService streamService
    ) {
        this.notificationService = notificationService;
        this.streamService = streamService;
    }

    @GetMapping
    public ResponseEntity<UserNotificationService.NotificationListResponse> list(
            Authentication authentication,
            @RequestParam(name = "limit", defaultValue = "50")
            @Min(1) @Max(100) int limit
    ) {
        Long userId = requireUserId(authentication);
        return ResponseEntity.ok(notificationService.list(userId, limit));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<UserNotificationService.UserNotificationListItem> markRead(
            Authentication authentication,
            @PathVariable("id") Long notificationId
    ) {
        Long userId = requireUserId(authentication);
        return ResponseEntity.ok(notificationService.markRead(userId, notificationId));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismiss(
            Authentication authentication,
            @PathVariable("id") Long notificationId
    ) {
        Long userId = requireUserId(authentication);
        notificationService.dismiss(userId, notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<MarkAllReadResponse> markAllRead(Authentication authentication) {
        Long userId = requireUserId(authentication);
        long updated = notificationService.markAllRead(userId);
        return ResponseEntity.ok(new MarkAllReadResponse(updated));
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication) {
        Long userId = requireUserId(authentication);
        SseEmitter emitter = streamService.open(userId);
        notificationService.publishRefresh(userId);
        return emitter;
    }

    private static Long requireUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication is required");
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof JwtCookieAuthenticationFilter.AuthenticatedUser user) || user.id() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication is required");
        }
        return user.id();
    }

    public record MarkAllReadResponse(long updated) {}
}

