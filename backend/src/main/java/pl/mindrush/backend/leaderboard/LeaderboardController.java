package pl.mindrush.backend.leaderboard;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.game.GameAnswerRepository;
import pl.mindrush.backend.game.GameSessionRepository;
import pl.mindrush.backend.game.GameStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
public class LeaderboardController {

    private final AppUserRepository userRepository;
    private final GameSessionRepository gameSessionRepository;
    private final GameAnswerRepository gameAnswerRepository;

    public LeaderboardController(
            AppUserRepository userRepository,
            GameSessionRepository gameSessionRepository,
            GameAnswerRepository gameAnswerRepository
    ) {
        this.userRepository = userRepository;
        this.gameSessionRepository = gameSessionRepository;
        this.gameAnswerRepository = gameAnswerRepository;
    }

    @GetMapping("/api/leaderboard")
    public List<LeaderboardEntryDto> leaderboard(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        PageRequest pr = pageRequest(page, size, limit);
        List<AppUser> users = userRepository.findAllByOrderByRankPointsDescIdAsc(pr);
        Map<String, Long> duplicateCounts = duplicateCounts(users);

        return users.stream().map(u -> toDto(u, duplicateCounts)).toList();
    }

    @GetMapping("/api/leaderboard/stats")
    public LeaderboardStatsDto stats() {
        long players = userRepository.count();
        long matches = gameSessionRepository.countByStatus(GameStatus.FINISHED);
        long answers = gameAnswerRepository.count();
        return new LeaderboardStatsDto(players, matches, answers);
    }

    @GetMapping("/api/leaderboard/me")
    public ResponseEntity<LeaderboardMeDto> me(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return ResponseEntity.status(401).build();
        }
        Object p = authentication.getPrincipal();
        if (!(p instanceof pl.mindrush.backend.JwtCookieAuthenticationFilter.AuthenticatedUser au)) {
            return ResponseEntity.status(401).build();
        }

        return userRepository.findById(au.id())
                .map(u -> {
                    Map<String, Long> counts = duplicateCounts(List.of(u));
                    long position = userRepository.countAhead(u.getRankPoints(), u.getId()) + 1L;
                    LeaderboardEntryDto dto = toDto(u, counts);
                    return ResponseEntity.ok(new LeaderboardMeDto(
                            u.getId(),
                            dto.displayName(),
                            u.getRankPoints(),
                            position,
                            dto.isPremium()
                    ));
                })
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    private static String baseName(AppUser u) {
        String name = u.getDisplayName();
        if (name == null || name.isBlank()) {
            return "Player-" + u.getId();
        }
        return name.trim();
    }

    private PageRequest pageRequest(Integer pageRaw, Integer sizeRaw, int limitRaw) {
        if (pageRaw == null && sizeRaw == null) {
            int safeLimit = Math.max(1, Math.min(200, limitRaw));
            return PageRequest.of(0, safeLimit);
        }

        int page = pageRaw == null ? 1 : pageRaw;
        int size = sizeRaw == null ? 50 : sizeRaw;
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(200, size));
        return PageRequest.of(safePage - 1, safeSize);
    }

    private Map<String, Long> duplicateCounts(List<AppUser> users) {
        List<String> namesLower = users.stream()
                .map(AppUser::getDisplayName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase())
                .distinct()
                .toList();

        if (namesLower.isEmpty()) return Map.of();

        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : userRepository.countByDisplayNameLowerIn(namesLower)) {
            if (row == null || row.length < 2) continue;
            String name = String.valueOf(row[0]);
            long c = ((Number) row[1]).longValue();
            counts.put(name, c);
        }
        return counts;
    }

    private static LeaderboardEntryDto toDto(AppUser u, Map<String, Long> nameCounts) {
        String base = baseName(u);
        String key = (u.getDisplayName() == null) ? "" : u.getDisplayName().trim().toLowerCase();
        boolean duplicate = !key.isBlank() && nameCounts.getOrDefault(key, 0L) > 1L;
        String display = duplicate ? base + "#" + u.getId() : base;
        boolean premium = u.getRoles().contains(AppRole.PREMIUM);
        return new LeaderboardEntryDto(u.getId(), display, u.getRankPoints(), premium);
    }

    public record LeaderboardEntryDto(long userId, String displayName, int rankPoints, boolean isPremium) {
    }

    public record LeaderboardStatsDto(long players, long matches, long answers) {
    }

    public record LeaderboardMeDto(long userId, String displayName, int rankPoints, long position, boolean isPremium) {
    }
}
