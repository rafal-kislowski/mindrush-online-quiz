package pl.mindrush.backend.achievement;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.game.GameAnswerRepository;
import pl.mindrush.backend.game.GamePlayerRepository;
import pl.mindrush.backend.game.GameStatus;
import pl.mindrush.backend.guest.GuestSessionRepository;
import pl.mindrush.backend.notification.UserNotificationService;
import pl.mindrush.backend.quiz.QuizModerationStatus;
import pl.mindrush.backend.quiz.QuizRepository;
import pl.mindrush.backend.quiz.QuizStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional
public class UserAchievementService {

    private static final String SOLO_LOBBY_ID_PATTERN = "SOLO%";
    private static final List<Definition> DEFINITIONS = buildDefinitions();

    private final UserAchievementUnlockRepository unlockRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final GameAnswerRepository gameAnswerRepository;
    private final AppUserRepository appUserRepository;
    private final QuizRepository quizRepository;
    private final UserNotificationService notificationService;
    private final Clock clock;

    public UserAchievementService(
            UserAchievementUnlockRepository unlockRepository,
            GuestSessionRepository guestSessionRepository,
            GamePlayerRepository gamePlayerRepository,
            GameAnswerRepository gameAnswerRepository,
            AppUserRepository appUserRepository,
            QuizRepository quizRepository,
            UserNotificationService notificationService,
            Clock clock
    ) {
        this.unlockRepository = unlockRepository;
        this.guestSessionRepository = guestSessionRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.gameAnswerRepository = gameAnswerRepository;
        this.appUserRepository = appUserRepository;
        this.quizRepository = quizRepository;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    private static List<Definition> buildDefinitions() {
        List<Definition> defs = new ArrayList<>();
        addTieredDefinitions(
                defs,
                "games_finished",
                "Game Finisher",
                "fa-solid fa-flag-checkered",
                Category.GAMEPLAY,
                Metric.TOTAL_GAMES,
                new int[]{1, 10, 25, 50, 100, 250, 500, 1000, 3000}
        );
        addTieredDefinitions(
                defs,
                "arena_warrior",
                "Arena Warrior",
                "fa-solid fa-users",
                Category.COMPETITIVE,
                Metric.LOBBY_GAMES,
                new int[]{1, 10, 25, 50, 100, 250, 500, 1000, 2500}
        );
        addTieredDefinitions(
                defs,
                "solo_explorer",
                "Solo Explorer",
                "fa-solid fa-user",
                Category.GAMEPLAY,
                Metric.SOLO_GAMES,
                new int[]{1, 10, 25, 50, 100, 250, 500, 1000, 2500}
        );
        addTieredDefinitions(
                defs,
                "precision_master",
                "Precision",
                "fa-solid fa-bullseye",
                Category.GAMEPLAY,
                Metric.CORRECT_ANSWERS,
                new int[]{25, 100, 250, 500, 1000, 2500, 5000, 10000, 25000}
        );
        addTieredDefinitions(
                defs,
                "xp_engine",
                "XP Engine",
                "fa-solid fa-bolt",
                Category.PROGRESSION,
                Metric.XP,
                new int[]{1000, 5000, 10000, 25000, 50000, 100000, 250000, 500000, 1000000}
        );
        addTieredDefinitions(
                defs,
                "ranked_climber",
                "Ranked Climber",
                "fa-solid fa-chart-line",
                Category.PROGRESSION,
                Metric.RANK_POINTS,
                new int[]{500, 1000, 1500, 2000, 3000, 4200, 5200, 5800, 6500}
        );
        addTieredDefinitions(
                defs,
                "vault_keeper",
                "Vault Keeper",
                "fa-solid fa-coins",
                Category.PROGRESSION,
                Metric.COINS,
                new int[]{1000, 5000, 10000, 25000, 50000, 100000, 250000, 500000, 1000000}
        );
        addTieredDefinitions(
                defs,
                "creator",
                "Creator",
                "fa-solid fa-pen-nib",
                Category.CREATOR,
                Metric.APPROVED_QUIZZES,
                new int[]{1, 3, 5, 10, 20, 40, 75, 120, 200}
        );
        return List.copyOf(defs);
    }

    private static void addTieredDefinitions(
            List<Definition> sink,
            String keyBase,
            String titleBase,
            String icon,
            Category category,
            Metric metric,
            int[] milestones
    ) {
        AchievementTier[] tiers = AchievementTier.values();
        if (milestones.length != tiers.length) {
            throw new IllegalArgumentException("Milestones count must match tier count for " + keyBase);
        }
        for (int i = 0; i < tiers.length; i++) {
            AchievementTier tier = tiers[i];
            int target = Math.max(1, milestones[i]);
            sink.add(new Definition(
                    keyBase + "_" + tier.key(),
                    titleBase + " " + tier.label(),
                    descriptionFor(metric, target),
                    icon,
                    category,
                    metric,
                    tier,
                    target
            ));
        }
    }

    private static String descriptionFor(Metric metric, int target) {
        String amount = formatNumber(target);
        if (metric == Metric.TOTAL_GAMES) {
            return "Complete " + amount + " finished " + plural(target, "game", "games") + ".";
        }
        if (metric == Metric.LOBBY_GAMES) {
            return "Play " + amount + " multiplayer " + plural(target, "game", "games") + ".";
        }
        if (metric == Metric.SOLO_GAMES) {
            return "Play " + amount + " solo " + plural(target, "game", "games") + ".";
        }
        if (metric == Metric.CORRECT_ANSWERS) {
            return "Give " + amount + " correct " + plural(target, "answer", "answers") + " in finished games.";
        }
        if (metric == Metric.XP) {
            return "Reach " + amount + " XP.";
        }
        if (metric == Metric.RANK_POINTS) {
            return "Reach " + amount + " rank points.";
        }
        if (metric == Metric.COINS) {
            return "Collect " + amount + " coins.";
        }
        return "Get " + amount + " " + plural(target, "quiz", "quizzes") + " approved in the library.";
    }

    private static String formatNumber(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static String plural(int count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

    public AchievementListResponse list(Long userId) {
        refreshForUser(userId, clock.instant());
        return buildResponse(userId);
    }

    public void refreshForUser(Long userId) {
        refreshForUser(userId, clock.instant());
    }

    public void refreshForUser(Long userId, Instant now) {
        if (userId == null) return;
        AppUser user = appUserRepository.findById(userId).orElse(null);
        if (user == null) return;

        MetricSnapshot snapshot = buildSnapshot(userId, user);
        Map<String, Instant> unlockedAtByKey = unlockedAtByKey(userId);
        Instant unlockedAt = now != null ? now : clock.instant();

        for (Definition definition : DEFINITIONS) {
            int currentValue = metricValue(snapshot, definition.metric());
            if (currentValue < definition.target()) continue;
            if (unlockedAtByKey.containsKey(definition.key())) continue;

            UserAchievementUnlock unlock = new UserAchievementUnlock();
            unlock.setUserId(userId);
            unlock.setAchievementKey(definition.key());
            unlock.setUnlockedAt(unlockedAt);
            try {
                unlockRepository.save(unlock);
            } catch (DataIntegrityViolationException ignored) {
                // Another transaction unlocked it first.
            }
            unlockedAtByKey.putIfAbsent(definition.key(), unlockedAt);
            notificationService.createAchievementUnlockedNotification(
                    userId,
                    definition.key(),
                    definition.title(),
                    definition.description(),
                    definition.icon(),
                    unlockedAt
            );
        }
    }

    private AchievementListResponse buildResponse(Long userId) {
        AppUser user = appUserRepository.findById(userId).orElse(null);
        if (user == null) {
            return new AchievementListResponse(
                    "Achievements",
                    "Unlock milestones by playing and progressing.",
                    DEFINITIONS.size(),
                    0,
                    0,
                    List.of()
            );
        }

        MetricSnapshot snapshot = buildSnapshot(userId, user);
        Map<String, Instant> unlockedAtByKey = unlockedAtByKey(userId);

        List<AchievementListItem> items = DEFINITIONS.stream()
                .map(definition -> {
                    int value = metricValue(snapshot, definition.metric());
                    int progress = Math.max(0, Math.min(definition.target(), value));
                    Instant unlockedAt = unlockedAtByKey.get(definition.key());
                    boolean unlocked = unlockedAt != null || value >= definition.target();
                    return new AchievementListItem(
                            definition.key(),
                            definition.title(),
                            definition.description(),
                            definition.icon(),
                            definition.category().label(),
                            definition.tier().key(),
                            definition.tier().color(),
                            definition.target(),
                            progress,
                            unlocked,
                            unlockedAt
                    );
                })
                .toList();

        int unlockedCount = (int) items.stream().filter(AchievementListItem::unlocked).count();
        int totalCount = Math.max(1, items.size());
        int completionPct = Math.max(0, Math.min(100, Math.round((unlockedCount * 100f) / totalCount)));

        return new AchievementListResponse(
                "Achievements",
                "Unlock milestones by playing and progressing.",
                items.size(),
                unlockedCount,
                completionPct,
                items
        );
    }

    private MetricSnapshot buildSnapshot(Long userId, AppUser user) {
        List<String> guestSessionIds = guestSessionRepository.findIdsByUserId(userId);
        long totalGames = 0L;
        long lobbyGames = 0L;
        long soloGames = 0L;
        long correctAnswers = 0L;

        if (guestSessionIds != null && !guestSessionIds.isEmpty()) {
            totalGames = gamePlayerRepository.countDistinctFinishedSessionsByGuestSessionIds(
                    guestSessionIds,
                    GameStatus.FINISHED
            );
            soloGames = gamePlayerRepository.countDistinctFinishedSessionsByGuestSessionIdsAndLobbyIdLike(
                    guestSessionIds,
                    GameStatus.FINISHED,
                    SOLO_LOBBY_ID_PATTERN
            );
            lobbyGames = gamePlayerRepository.countDistinctFinishedSessionsByGuestSessionIdsAndLobbyIdNotLike(
                    guestSessionIds,
                    GameStatus.FINISHED,
                    SOLO_LOBBY_ID_PATTERN
            );
            correctAnswers = gameAnswerRepository.countCorrectByGuestSessionIdsAndSessionStatus(
                    guestSessionIds,
                    GameStatus.FINISHED
            );
        }

        long approvedQuizzes = quizRepository.countByOwnerUserIdAndStatusAndModerationStatus(
                userId,
                QuizStatus.ACTIVE,
                QuizModerationStatus.APPROVED
        );

        return new MetricSnapshot(
                toInt(totalGames),
                toInt(lobbyGames),
                toInt(soloGames),
                toInt(correctAnswers),
                Math.max(0, user.getXp()),
                Math.max(0, user.getRankPoints()),
                Math.max(0, user.getCoins()),
                toInt(approvedQuizzes)
        );
    }

    private Map<String, Instant> unlockedAtByKey(Long userId) {
        Map<String, Instant> result = new LinkedHashMap<>();
        for (UserAchievementUnlock unlock : unlockRepository.findAllByUserIdOrderByUnlockedAtDesc(userId)) {
            String key = normalizeKey(unlock.getAchievementKey());
            if (key.isBlank()) continue;
            result.putIfAbsent(key, unlock.getUnlockedAt());
        }
        return result;
    }

    private static String normalizeKey(String value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    private static int metricValue(MetricSnapshot snapshot, Metric metric) {
        if (metric == Metric.TOTAL_GAMES) return snapshot.totalGames();
        if (metric == Metric.LOBBY_GAMES) return snapshot.lobbyGames();
        if (metric == Metric.SOLO_GAMES) return snapshot.soloGames();
        if (metric == Metric.CORRECT_ANSWERS) return snapshot.correctAnswers();
        if (metric == Metric.XP) return snapshot.xp();
        if (metric == Metric.RANK_POINTS) return snapshot.rankPoints();
        if (metric == Metric.COINS) return snapshot.coins();
        return snapshot.approvedQuizzes();
    }

    private static int toInt(long value) {
        if (value <= 0L) return 0;
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) value;
    }

    private enum Metric {
        TOTAL_GAMES,
        LOBBY_GAMES,
        SOLO_GAMES,
        CORRECT_ANSWERS,
        XP,
        RANK_POINTS,
        COINS,
        APPROVED_QUIZZES
    }

    private enum Category {
        GAMEPLAY("gameplay"),
        COMPETITIVE("competitive"),
        PROGRESSION("progression"),
        CREATOR("creator");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private enum AchievementTier {
        ROOKIE("rookie", "Rookie", "#9AA4B2"),
        BRONZE("bronze", "Bronze", "#CD7F32"),
        SILVER("silver", "Silver", "#C0C0C0"),
        GOLD("gold", "Gold", "#E6B800"),
        PLATINUM("platinum", "Platinum", "#00C2A8"),
        DIAMOND("diamond", "Diamond", "#3AA0FF"),
        MASTER("master", "Master", "#7B3FE4"),
        LEGEND("legend", "Legend", "#4B2EFF"),
        MYTHIC("mythic", "Mythic", "#E10600");

        private final String key;
        private final String label;
        private final String color;

        AchievementTier(String key, String label, String color) {
            this.key = key;
            this.label = label;
            this.color = color;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public String color() {
            return color;
        }
    }

    private record Definition(
            String key,
            String title,
            String description,
            String icon,
            Category category,
            Metric metric,
            AchievementTier tier,
            int target
    ) {
    }

    private record MetricSnapshot(
            int totalGames,
            int lobbyGames,
            int soloGames,
            int correctAnswers,
            int xp,
            int rankPoints,
            int coins,
            int approvedQuizzes
    ) {
    }

    public record AchievementListItem(
            String key,
            String title,
            String description,
            String icon,
            String category,
            String tier,
            String tierColor,
            int target,
            int progress,
            boolean unlocked,
            Instant unlockedAt
    ) {
    }

    public record AchievementListResponse(
            String title,
            String description,
            int totalCount,
            int unlockedCount,
            int completionPct,
            List<AchievementListItem> items
    ) {
    }
}
