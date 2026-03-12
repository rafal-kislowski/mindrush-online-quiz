package pl.mindrush.backend.achievement;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.JwtCookieAuthenticationFilter;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/achievements")
public class UserAchievementController {

    private final UserAchievementService achievementService;

    public UserAchievementController(UserAchievementService achievementService) {
        this.achievementService = achievementService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserAchievementService.AchievementListResponse> me(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ResponseEntity.ok(achievementService.list(userId));
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
}
