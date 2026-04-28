package pl.mindrush.backend.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.AuthActionTokenRepository;
import pl.mindrush.backend.RefreshTokenRepository;
import pl.mindrush.backend.achievement.UserAchievementUnlockRepository;
import pl.mindrush.backend.casual.CasualThreeLivesRecordRepository;
import pl.mindrush.backend.config.AppDemoProperties;
import pl.mindrush.backend.game.*;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionRepository;
import pl.mindrush.backend.lobby.*;
import pl.mindrush.backend.lobby.events.LobbyEventPublisher;
import pl.mindrush.backend.notification.UserNotificationRepository;
import pl.mindrush.backend.quiz.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Profile("demo")
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoDatasetService {

    private static final String DEMO_EMAIL_DOMAIN = "@demo.mindrush.local";

    private static final List<String> AUTH_PREFIXES = List.of(
            "Astra", "Blaze", "Cipher", "Drift", "Echo", "Flux", "Glint", "Halo", "Ion", "Jade",
            "Kairo", "Luma", "Mira", "Nova", "Onyx", "Pulse", "Quartz", "Rift", "Sol", "Talon",
            "Umbra", "Vivid", "Warden", "Xeno", "Yara", "Zen", "Atlas", "Briar", "Cinder", "Dune",
            "Ember", "Fable", "Gale", "Harbor", "Indra", "Juno", "Kite", "Lyra", "Mako", "Noble"
    );

    private static final List<String> AUTH_SUFFIXES = List.of(
            "Fox", "Wave", "Pilot", "Orbit", "Vale", "Spark", "Falcon", "Drake", "Runner", "Scout",
            "Raven", "Forge", "Comet", "Rider", "Atlas", "Byte", "Harbor", "Echo", "Nova", "Pulse",
            "Crest", "Glade", "Storm", "Cipher", "Grove", "Anchor", "Blitz", "Quill", "Vortex", "Trace",
            "Flare", "Stride", "Signal", "Lancer", "Voyage", "Frame", "Shift", "Frost", "Beacon", "Circuit"
    );

    private static final List<String> GUEST_PREFIXES = List.of(
            "Neon", "Pixel", "Rocket", "Comet", "Flash", "Turbo", "Sky", "Orbit", "Dash", "Sonic",
            "Laser", "Pulse", "Nova", "Cloud", "Prism"
    );

    private static final List<String> GUEST_SUFFIXES = List.of(
            "Guest", "Rider", "Drift", "Wave", "Fox", "Blink", "Rush", "Bolt", "Hop", "Loop",
            "Scout", "Spark", "Dash", "Shift", "Comet"
    );

    private static final List<Integer> AUTH_SHOWCASE_RANK_POINTS = List.of(
            6800, 6200, 5850, 5450, 5100, 4750,
            4400, 4100, 3850, 3550, 3300, 3050,
            2850, 2650, 2450, 2250, 2050, 1850,
            1650, 1450, 1250, 1100, 950, 800,
            650, 520, 400, 300, 220, 140,
            80, 30, 0, 0, 0, 0
    );

    private static final List<Integer> GUEST_SHOWCASE_RANK_POINTS = List.of(
            1700, 1250, 980, 760, 540, 360,
            220, 120, 40, 0, 0, 0,
            0, 0, 0
    );

    private final Clock clock;
    private final AppDemoProperties demoProperties;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final StarterQuizSeeder starterQuizSeeder;
    private final AppUserRepository appUserRepository;
    private final AuthActionTokenRepository authActionTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserAchievementUnlockRepository userAchievementUnlockRepository;
    private final CasualThreeLivesRecordRepository casualThreeLivesRecordRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final LobbyBanRepository lobbyBanRepository;
    private final LobbyParticipantRepository lobbyParticipantRepository;
    private final LobbyRepository lobbyRepository;
    private final GameAnswerRepository gameAnswerRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final GameSessionQuestionRepository gameSessionQuestionRepository;
    private final GameSessionRepository gameSessionRepository;
    private final QuizFavoriteRepository quizFavoriteRepository;
    private final QuizModerationIssueRepository quizModerationIssueRepository;
    private final QuizAnswerOptionRepository quizAnswerOptionRepository;
    private final QuizQuestionRepository quizQuestionRepository;
    private final QuizRepository quizRepository;
    private final QuizCategoryRepository quizCategoryRepository;
    private final GameService gameService;
    private final LobbyEventPublisher lobbyEventPublisher;
    private final ReentrantLock maintenanceLock = new ReentrantLock();

    public DemoDatasetService(
            Clock clock,
            AppDemoProperties demoProperties,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate,
            StarterQuizSeeder starterQuizSeeder,
            AppUserRepository appUserRepository,
            AuthActionTokenRepository authActionTokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserAchievementUnlockRepository userAchievementUnlockRepository,
            CasualThreeLivesRecordRepository casualThreeLivesRecordRepository,
            GuestSessionRepository guestSessionRepository,
            UserNotificationRepository userNotificationRepository,
            LobbyBanRepository lobbyBanRepository,
            LobbyParticipantRepository lobbyParticipantRepository,
            LobbyRepository lobbyRepository,
            GameAnswerRepository gameAnswerRepository,
            GamePlayerRepository gamePlayerRepository,
            GameSessionQuestionRepository gameSessionQuestionRepository,
            GameSessionRepository gameSessionRepository,
            QuizFavoriteRepository quizFavoriteRepository,
            QuizModerationIssueRepository quizModerationIssueRepository,
            QuizAnswerOptionRepository quizAnswerOptionRepository,
            QuizQuestionRepository quizQuestionRepository,
            QuizRepository quizRepository,
            QuizCategoryRepository quizCategoryRepository,
            GameService gameService,
            LobbyEventPublisher lobbyEventPublisher
    ) {
        this.clock = clock;
        this.demoProperties = demoProperties;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.starterQuizSeeder = starterQuizSeeder;
        this.appUserRepository = appUserRepository;
        this.authActionTokenRepository = authActionTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userAchievementUnlockRepository = userAchievementUnlockRepository;
        this.casualThreeLivesRecordRepository = casualThreeLivesRecordRepository;
        this.guestSessionRepository = guestSessionRepository;
        this.userNotificationRepository = userNotificationRepository;
        this.lobbyBanRepository = lobbyBanRepository;
        this.lobbyParticipantRepository = lobbyParticipantRepository;
        this.lobbyRepository = lobbyRepository;
        this.gameAnswerRepository = gameAnswerRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.gameSessionQuestionRepository = gameSessionQuestionRepository;
        this.gameSessionRepository = gameSessionRepository;
        this.quizFavoriteRepository = quizFavoriteRepository;
        this.quizModerationIssueRepository = quizModerationIssueRepository;
        this.quizAnswerOptionRepository = quizAnswerOptionRepository;
        this.quizQuestionRepository = quizQuestionRepository;
        this.quizRepository = quizRepository;
        this.quizCategoryRepository = quizCategoryRepository;
        this.gameService = gameService;
        this.lobbyEventPublisher = lobbyEventPublisher;
    }

    @Transactional
    public void bootstrap() {
        runLocked(() -> {
            if (demoProperties.getReset().isOnStartup()) {
                resetAndSeedLocked();
            } else {
                ensureDatasetLocked();
            }
            if (demoProperties.getSimulation().isEnabled()) {
                simulateLocked();
            }
        });
    }

    @Transactional
    public void scheduledReset() {
        runLocked(() -> {
            resetAndSeedLocked();
            if (demoProperties.getSimulation().isEnabled()) {
                simulateLocked();
            }
        });
    }

    @Transactional
    public void simulateTick() {
        runLocked(() -> {
            ensureDatasetLocked();
            simulateLocked();
        });
    }

    private void runLocked(Runnable action) {
        if (!maintenanceLock.tryLock()) return;
        try {
            action.run();
        } finally {
            maintenanceLock.unlock();
        }
    }

    private void resetAndSeedLocked() {
        clearDatabase();
        starterQuizSeeder.seedIfEmpty();
        ensureDatasetLocked();
        seedHistoricalMatches();
    }

    private void ensureDatasetLocked() {
        starterQuizSeeder.seedIfEmpty();
        seedDemoUsers();
        seedDemoGuests();
    }

    private void simulateLocked() {
        DemoRoster roster = loadRoster();
        if (roster.isEmpty()) return;

        touchDemoSessions(roster);
        List<Quiz> quizzes = activeQuizzes();
        if (quizzes.isEmpty()) return;

        long bucket = simulationBucket();
        for (ManagedLobbyBlueprint blueprint : buildBlueprints(roster)) {
            syncLobby(blueprint, roster, quizzes, bucket);
        }
    }

    private void clearDatabase() {
        jdbcTemplate.update("delete from shop_order_effects");
        jdbcTemplate.update("delete from shop_orders");
        lobbyBanRepository.deleteAllInBatch();
        lobbyParticipantRepository.deleteAllInBatch();
        gameAnswerRepository.deleteAllInBatch();
        gamePlayerRepository.deleteAllInBatch();
        gameSessionQuestionRepository.deleteAllInBatch();
        gameSessionRepository.deleteAllInBatch();
        userNotificationRepository.deleteAllInBatch();
        userAchievementUnlockRepository.deleteAllInBatch();
        casualThreeLivesRecordRepository.deleteAllInBatch();
        quizFavoriteRepository.deleteAllInBatch();
        quizModerationIssueRepository.deleteAllInBatch();
        authActionTokenRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        jdbcTemplate.update("delete from app_user_roles");
        lobbyRepository.deleteAllInBatch();
        guestSessionRepository.deleteAllInBatch();
        appUserRepository.deleteAllInBatch();
        quizAnswerOptionRepository.deleteAllInBatch();
        quizQuestionRepository.deleteAllInBatch();
        quizRepository.deleteAllInBatch();
        quizCategoryRepository.deleteAllInBatch();
    }

    private void seedDemoUsers() {
        Instant now = clock.instant();
        List<AuthBotProfile> profiles = buildAuthProfiles();
        for (int index = 0; index < profiles.size(); index++) {
            AuthBotProfile profile = profiles.get(index);
            AppUser user = appUserRepository.findByEmailIgnoreCase(profile.email()).orElse(null);
            if (user == null) {
                user = new AppUser(
                        profile.email(),
                        passwordEncoder.encode("demo-" + profile.key()),
                        profile.displayName(),
                        profile.roles(),
                        now.minus(Duration.ofDays(45L + index))
                );
            }
            user.setDisplayName(profile.displayName());
            user.setRoles(profile.roles());
            user.setRankPoints(profile.rankPoints());
            user.setXp(profile.xp());
            user.setCoins(profile.coins());
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(now.minus(Duration.ofDays(30L + index)));
            user.setLastLoginAt(now.minus(Duration.ofMinutes(15L + index * 2L)));
            user = appUserRepository.save(user);

            GuestSession session = guestSessionRepository.findById(authSessionId(profile.key())).orElse(null);
            if (session == null) {
                session = GuestSession.createNew(now.minus(Duration.ofDays(12L + index)), now.plus(sessionTtl()));
                session.setId(authSessionId(profile.key()));
            }
            session.setDisplayName(profile.displayName());
            session.setUserId(user.getId());
            session.setRankPoints(profile.rankPoints());
            session.setXp(profile.xp());
            session.setCoins(profile.coins());
            session.setRevoked(false);
            session.setLastSeenAt(now.minus(Duration.ofMinutes(index % 7)));
            session.setExpiresAt(now.plus(sessionTtl()));
            guestSessionRepository.save(session);
        }
    }

    private void seedDemoGuests() {
        Instant now = clock.instant();
        List<GuestBotProfile> profiles = buildGuestProfiles();
        for (int index = 0; index < profiles.size(); index++) {
            GuestBotProfile profile = profiles.get(index);
            GuestSession session = guestSessionRepository.findById(guestSessionId(profile.key())).orElse(null);
            if (session == null) {
                session = GuestSession.createNew(now.minus(Duration.ofDays(8L + index)), now.plus(sessionTtl()));
                session.setId(guestSessionId(profile.key()));
            }
            session.setDisplayName(profile.displayName());
            session.setUserId(null);
            session.setRankPoints(profile.rankPoints());
            session.setXp(profile.xp());
            session.setCoins(profile.coins());
            session.setRevoked(false);
            session.setLastSeenAt(now.minus(Duration.ofMinutes(index % 5)));
            session.setExpiresAt(now.plus(sessionTtl()));
            guestSessionRepository.save(session);
        }
    }

    private void seedHistoricalMatches() {
        DemoRoster roster = loadRoster();
        if (roster.authenticated().size() < 2) return;

        List<Quiz> quizzes = activeQuizzes();
        if (quizzes.isEmpty()) return;

        Map<Long, QuizBundle> bundles = loadQuizBundles(quizzes);
        Instant now = clock.instant();
        int total = Math.max(0, demoProperties.getBootstrap().getHistoricalMatches());

        for (int matchIndex = 0; matchIndex < total; matchIndex++) {
            Quiz quiz = quizzes.get(matchIndex % quizzes.size());
            QuizBundle bundle = bundles.get(quiz.getId());
            if (bundle == null || bundle.questions().isEmpty()) continue;

            List<DemoSessionSlot> players = selectHistoricalPlayers(roster.authenticated(), matchIndex);
            Instant startedAt = now.minus(Duration.ofHours(36)).plus(Duration.ofMinutes(matchIndex * 17L));
            int questionDurationMs = Math.max(5, quiz.getQuestionTimeLimitSeconds()) * 1_000;
            GameSession session = GameSession.startNew(
                    "DEMOHIST-" + String.format(Locale.ROOT, "%04d", matchIndex),
                    quiz.getId(),
                    GameSessionMode.STANDARD,
                    startedAt,
                    Duration.ofSeconds(4),
                    questionDurationMs,
                    false,
                    false,
                    false
            );
            gameSessionRepository.save(session);

            for (int questionIndex = 0; questionIndex < bundle.questions().size(); questionIndex++) {
                gameSessionQuestionRepository.save(GameSessionQuestion.create(
                        session.getId(),
                        bundle.questions().get(questionIndex).getId(),
                        questionIndex
                ));
            }

            for (int playerIndex = 0; playerIndex < players.size(); playerIndex++) {
                DemoSessionSlot player = players.get(playerIndex);
                gamePlayerRepository.save(GamePlayer.create(session, player.sessionId(), player.displayName(), playerIndex + 1));
            }

            Random rng = new Random(seedFor("historical:" + matchIndex, matchIndex));
            for (int questionIndex = 0; questionIndex < bundle.questions().size(); questionIndex++) {
                QuizQuestion question = bundle.questions().get(questionIndex);
                List<QuizAnswerOption> options = bundle.optionsByQuestionId().getOrDefault(question.getId(), List.of());
                QuizAnswerOption correct = options.stream().filter(QuizAnswerOption::isCorrect).findFirst().orElse(null);
                List<QuizAnswerOption> wrongOptions = options.stream().filter(option -> !option.isCorrect()).toList();
                if (correct == null) continue;

                Instant questionStartedAt = startedAt.plus(Duration.ofSeconds(8L + questionIndex * Math.max(8L, quiz.getQuestionTimeLimitSeconds())));
                for (DemoSessionSlot player : players) {
                    int answerTimeMs = 1_200 + rng.nextInt(Math.max(1, questionDurationMs - 1_500));
                    boolean answeredCorrectly = wrongOptions.isEmpty() || rng.nextDouble() < player.skill();
                    QuizAnswerOption selected = answeredCorrectly
                            ? correct
                            : wrongOptions.get(rng.nextInt(wrongOptions.size()));
                    int points = answeredCorrectly
                            ? 100 + Math.max(0, Math.min(50, (questionDurationMs - answerTimeMs) / 250))
                            : 0;
                    gameAnswerRepository.save(GameAnswer.create(
                            session,
                            question.getId(),
                            player.sessionId(),
                            selected.getId(),
                            answeredCorrectly,
                            answerTimeMs,
                            points,
                            questionStartedAt.plusMillis(answerTimeMs)
                    ));
                }
            }

            Instant endedAt = startedAt.plus(Duration.ofSeconds(25L + (long) bundle.questions().size() * Math.max(8L, quiz.getQuestionTimeLimitSeconds() + 2L)));
            session.setStage(GameStage.REVEAL);
            session.setStageEndsAt(endedAt);
            session.setCurrentQuestionIndex(Math.max(0, bundle.questions().size() - 1));
            session.setStatus(GameStatus.FINISHED);
            session.setEndedAt(endedAt);
            session.setFinishReason(GameFinishReason.COMPLETED);
            session.setLastActivityAt(endedAt);
            session.setRewardsApplied(true);
            session.setRewardsAppliedAt(endedAt);
            gameSessionRepository.save(session);
        }
    }

    private List<Quiz> activeQuizzes() {
        return quizRepository.findAllWithCategoryByStatus(QuizStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(Quiz::getId))
                .toList();
    }

    private Map<Long, QuizBundle> loadQuizBundles(List<Quiz> quizzes) {
        Map<Long, QuizBundle> out = new LinkedHashMap<>();
        for (Quiz quiz : quizzes) {
            List<QuizQuestion> questions = quizQuestionRepository.findAllByQuizIdOrderByOrderIndexAsc(quiz.getId());
            if (questions.isEmpty()) continue;

            List<Long> questionIds = questions.stream().map(QuizQuestion::getId).toList();
            Map<Long, List<QuizAnswerOption>> optionsByQuestionId = new LinkedHashMap<>();
            for (QuizAnswerOption option : quizAnswerOptionRepository.findAllByQuestionIdInOrderByQuestionIdAscOrderIndexAsc(questionIds)) {
                optionsByQuestionId.computeIfAbsent(option.getQuestion().getId(), __ -> new ArrayList<>()).add(option);
            }
            out.put(quiz.getId(), new QuizBundle(questions, optionsByQuestionId));
        }
        return out;
    }

    private List<DemoSessionSlot> selectHistoricalPlayers(List<DemoSessionSlot> authenticated, int matchIndex) {
        List<DemoSessionSlot> copy = new ArrayList<>(authenticated);
        Collections.rotate(copy, matchIndex % Math.max(1, copy.size()));
        int count = 2 + Math.floorMod(matchIndex, 3);
        return new ArrayList<>(copy.subList(0, Math.min(count, copy.size())));
    }

    private void touchDemoSessions(DemoRoster roster) {
        Instant now = clock.instant();
        List<GuestSession> sessions = new ArrayList<>();
        for (DemoSessionSlot slot : roster.allSlots()) {
            GuestSession session = slot.session();
            session.setLastSeenAt(now);
            session.setExpiresAt(now.plus(sessionTtl()));
            session.setRevoked(false);
            if (slot.user() != null) {
                session.setDisplayName(slot.user().getDisplayName());
                session.setUserId(slot.user().getId());
                session.setRankPoints(slot.user().getRankPoints());
                session.setXp(slot.user().getXp());
                session.setCoins(slot.user().getCoins());
            }
            sessions.add(session);
        }
        if (!sessions.isEmpty()) {
            guestSessionRepository.saveAll(sessions);
        }
    }

    private void syncLobby(ManagedLobbyBlueprint blueprint, DemoRoster roster, List<Quiz> quizzes, long bucket) {
        Lobby lobby = lobbyRepository.findByCode(blueprint.code()).orElse(null);
        Instant now = clock.instant();

        if (lobby == null) {
            DemoSessionSlot owner = blueprint.owner();
            lobby = Lobby.createNew(
                    blueprint.code(),
                    owner.sessionId(),
                    blueprint.ownerAuthenticated(),
                    blueprint.maxPlayers(),
                    null,
                    null,
                    now.minus(Duration.ofMinutes(blueprint.createdMinutesAgo()))
            );
            lobby.setSelectedQuizId(pickQuiz(quizzes, blueprint.code()).getId());
            lobbyRepository.save(lobby);
        }

        List<LobbyParticipant> currentParticipants = lobbyParticipantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
        if (hasRealParticipants(currentParticipants, roster)) {
            return;
        }

        Quiz desiredQuiz = pickQuiz(quizzes, blueprint.code());
        boolean lobbyChanged = false;
        if (!Objects.equals(lobby.getOwnerGuestSessionId(), blueprint.owner().sessionId())) {
            lobby.setOwnerGuestSessionId(blueprint.owner().sessionId());
            lobbyChanged = true;
        }
        if (lobby.isOwnerAuthenticated() != blueprint.ownerAuthenticated()) {
            lobby.setOwnerAuthenticated(blueprint.ownerAuthenticated());
            lobbyChanged = true;
        }
        if (lobby.getMaxPlayers() != blueprint.maxPlayers()) {
            lobby.setMaxPlayers(blueprint.maxPlayers());
            lobbyChanged = true;
        }
        if (!Objects.equals(lobby.getSelectedQuizId(), desiredQuiz.getId())) {
            lobby.setSelectedQuizId(desiredQuiz.getId());
            lobbyChanged = true;
        }
        if (lobby.isRankingEnabled()) {
            lobby.setRankingEnabled(false);
            lobbyChanged = true;
        }
        if (lobby.getStatus() == LobbyStatus.CLOSED) {
            lobby.setStatus(LobbyStatus.OPEN);
            lobbyChanged = true;
        }
        if (lobbyChanged) {
            lobbyRepository.save(lobby);
        }

        if (lobby.getStatus() == LobbyStatus.IN_GAME) {
            return;
        }

        List<DemoSessionSlot> desiredParticipants = desiredParticipants(blueprint, bucket);
        Set<String> desiredIds = desiredParticipants.stream()
                .map(DemoSessionSlot::sessionId)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        boolean participantsChanged = false;
        for (LobbyParticipant participant : currentParticipants) {
            if (desiredIds.contains(participant.getGuestSessionId())) {
                if (participant.isReady()) {
                    participant.setReady(false);
                    lobbyParticipantRepository.save(participant);
                    participantsChanged = true;
                }
                continue;
            }
            lobbyParticipantRepository.delete(participant);
            participantsChanged = true;
        }

        Set<String> existingIds = lobbyParticipantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId()).stream()
                .map(LobbyParticipant::getGuestSessionId)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        int order = 0;
        for (DemoSessionSlot slot : desiredParticipants) {
            if (existingIds.contains(slot.sessionId())) continue;
            lobbyParticipantRepository.save(LobbyParticipant.createGuest(
                    lobby,
                    slot.sessionId(),
                    slot.displayName(),
                    now.minusSeconds(desiredParticipants.size() - order)
            ));
            order++;
            participantsChanged = true;
        }

        if (participantsChanged || lobbyChanged) {
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        }

        if (!blueprint.live()) {
            return;
        }

        List<LobbyParticipant> liveParticipants = lobbyParticipantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
        if (liveParticipants.size() < 2) {
            return;
        }
        for (LobbyParticipant participant : liveParticipants) {
            if (!participant.isReady()) {
                participant.setReady(true);
                lobbyParticipantRepository.save(participant);
            }
        }
        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        gameService.tryStartGameFromReady(lobby.getCode());
    }

    private boolean hasRealParticipants(List<LobbyParticipant> participants, DemoRoster roster) {
        if (participants == null || participants.isEmpty()) return false;
        Set<String> demoSessionIds = roster.allSessionIds();
        for (LobbyParticipant participant : participants) {
            if (!demoSessionIds.contains(participant.getGuestSessionId())) {
                return true;
            }
        }
        return false;
    }

    private List<ManagedLobbyBlueprint> buildBlueprints(DemoRoster roster) {
        List<ManagedLobbyBlueprint> blueprints = new ArrayList<>();
        int authCursor = 0;
        int guestCursor = 0;
        int authPoolSize = Math.max(2, demoProperties.getSimulation().getAuthenticatedLobbyPoolSize());
        int guestPoolSize = Math.max(2, demoProperties.getSimulation().getGuestLobbyPoolSize());

        for (int index = 0; index < Math.max(0, demoProperties.getSimulation().getOpenGuestLobbies()); index++) {
            List<DemoSessionSlot> pool = slice(roster.guests(), guestCursor, guestPoolSize);
            if (pool.size() < 2) break;
            guestCursor += pool.size();
            blueprints.add(new ManagedLobbyBlueprint(
                    String.format(Locale.ROOT, "DGO%03d", index + 1),
                    false,
                    pool,
                    LobbyService.GUEST_LOBBY_MAX_PLAYERS,
                    1,
                    2,
                    false,
                    6 + index * 3
            ));
        }

        for (int index = 0; index < Math.max(0, demoProperties.getSimulation().getOpenAuthenticatedLobbies()); index++) {
            List<DemoSessionSlot> pool = slice(roster.authenticated(), authCursor, authPoolSize);
            if (pool.size() < 2) break;
            authCursor += pool.size();
            int maxPlayers = Math.min(LobbyService.AUTH_LOBBY_MAX_PLAYERS, pool.size());
            blueprints.add(new ManagedLobbyBlueprint(
                    String.format(Locale.ROOT, "DAO%03d", index + 1),
                    true,
                    pool,
                    maxPlayers,
                    2,
                    Math.max(2, maxPlayers - 1),
                    false,
                    2 + index * 3
            ));
        }

        for (int index = 0; index < Math.max(0, demoProperties.getSimulation().getInGameLobbies()); index++) {
            List<DemoSessionSlot> pool = slice(roster.authenticated(), authCursor, authPoolSize);
            if (pool.size() < 2) break;
            authCursor += pool.size();
            int maxPlayers = Math.min(4, pool.size());
            blueprints.add(new ManagedLobbyBlueprint(
                    String.format(Locale.ROOT, "DLI%03d", index + 1),
                    true,
                    pool,
                    maxPlayers,
                    maxPlayers,
                    maxPlayers,
                    true,
                    1
            ));
        }

        return blueprints;
    }

    private List<DemoSessionSlot> desiredParticipants(ManagedLobbyBlueprint blueprint, long bucket) {
        List<DemoSessionSlot> others = new ArrayList<>(blueprint.pool().subList(1, blueprint.pool().size()));
        Collections.shuffle(others, new Random(seedFor(blueprint.code(), bucket)));

        int minPlayers = Math.max(1, blueprint.minDesiredPlayers());
        int maxPlayers = Math.max(minPlayers, blueprint.maxDesiredPlayers());
        int span = maxPlayers - minPlayers + 1;
        int desiredCount = minPlayers + (int) Math.floorMod(seedFor("count:" + blueprint.code(), bucket), span);
        desiredCount = Math.min(desiredCount, Math.min(blueprint.maxPlayers(), blueprint.pool().size()));

        List<DemoSessionSlot> out = new ArrayList<>();
        out.add(blueprint.owner());
        for (DemoSessionSlot slot : others) {
            if (out.size() >= desiredCount) break;
            out.add(slot);
        }
        return out;
    }

    private DemoRoster loadRoster() {
        Map<String, AppUser> usersByEmail = new LinkedHashMap<>();
        for (AppUser user : appUserRepository.findAll()) {
            String email = user.getEmail() == null ? "" : user.getEmail().trim().toLowerCase(Locale.ROOT);
            if (email.endsWith(DEMO_EMAIL_DOMAIN)) {
                usersByEmail.put(email, user);
            }
        }

        Set<String> sessionIds = new LinkedHashSet<>();
        for (AuthBotProfile profile : buildAuthProfiles()) sessionIds.add(authSessionId(profile.key()));
        for (GuestBotProfile profile : buildGuestProfiles()) sessionIds.add(guestSessionId(profile.key()));

        Map<String, GuestSession> sessionsById = new LinkedHashMap<>();
        for (GuestSession session : guestSessionRepository.findAllById(sessionIds)) {
            sessionsById.put(session.getId(), session);
        }

        List<DemoSessionSlot> authenticated = new ArrayList<>();
        for (AuthBotProfile profile : buildAuthProfiles()) {
            AppUser user = usersByEmail.get(profile.email());
            GuestSession session = sessionsById.get(authSessionId(profile.key()));
            if (user != null && session != null) {
                authenticated.add(new DemoSessionSlot(profile.key(), session.getDisplayName(), session.getId(), user, session));
            }
        }

        List<DemoSessionSlot> guests = new ArrayList<>();
        for (GuestBotProfile profile : buildGuestProfiles()) {
            GuestSession session = sessionsById.get(guestSessionId(profile.key()));
            if (session != null) {
                guests.add(new DemoSessionSlot(profile.key(), session.getDisplayName(), session.getId(), null, session));
            }
        }

        return new DemoRoster(authenticated, guests);
    }

    private List<AuthBotProfile> buildAuthProfiles() {
        int count = Math.min(Math.max(0, demoProperties.getBootstrap().getAuthenticatedUsers()), AUTH_PREFIXES.size());
        List<AuthBotProfile> out = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String key = String.format(Locale.ROOT, "auth-%02d", index + 1);
            String displayName = AUTH_PREFIXES.get(index) + AUTH_SUFFIXES.get((index * 7 + 3) % AUTH_SUFFIXES.size());
            boolean premium = index % 6 == 0 || index % 11 == 0;
            Set<AppRole> roles = premium ? EnumSet.of(AppRole.USER, AppRole.PREMIUM) : EnumSet.of(AppRole.USER);
            int rankPoints = showcaseRankPoints(index, AUTH_SHOWCASE_RANK_POINTS, 0);
            int xp = 24_000 + (count - index) * 1_350;
            int coins = 1_000 + (count - index) * 120;
            out.add(new AuthBotProfile(key, displayName, key + DEMO_EMAIL_DOMAIN, roles, rankPoints, xp, coins));
        }
        return out;
    }

    private List<GuestBotProfile> buildGuestProfiles() {
        int count = Math.min(Math.max(0, demoProperties.getBootstrap().getGuestBots()), GUEST_PREFIXES.size());
        List<GuestBotProfile> out = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String key = String.format(Locale.ROOT, "guest-%02d", index + 1);
            String displayName = GUEST_PREFIXES.get(index) + GUEST_SUFFIXES.get((index * 5 + 2) % GUEST_SUFFIXES.size());
            int rankPoints = showcaseRankPoints(index, GUEST_SHOWCASE_RANK_POINTS, 0);
            int xp = 3_500 + (count - index) * 420;
            int coins = 180 + (count - index) * 45;
            out.add(new GuestBotProfile(key, displayName, rankPoints, xp, coins));
        }
        return out;
    }

    private static int showcaseRankPoints(int index, List<Integer> showcase, int fallback) {
        if (index < 0) return Math.max(0, fallback);
        if (showcase != null && index < showcase.size()) {
            return Math.max(0, showcase.get(index));
        }
        return Math.max(0, fallback);
    }

    private Quiz pickQuiz(List<Quiz> quizzes, String code) {
        return quizzes.get(Math.floorMod(code.hashCode(), quizzes.size()));
    }

    private List<DemoSessionSlot> slice(List<DemoSessionSlot> source, int offset, int size) {
        if (offset >= source.size()) return List.of();
        return new ArrayList<>(source.subList(offset, Math.min(source.size(), offset + size)));
    }

    private Duration sessionTtl() {
        Duration ttl = demoProperties.getSimulation().getSessionTtl();
        return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofHours(12) : ttl;
    }

    private long simulationBucket() {
        return clock.instant().toEpochMilli() / Math.max(1L, demoProperties.getSimulation().getFixedDelayMs());
    }

    private static String authSessionId(String key) {
        return UUID.nameUUIDFromBytes(("mindrush-demo-auth-session:" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String guestSessionId(String key) {
        return UUID.nameUUIDFromBytes(("mindrush-demo-guest-session:" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static long seedFor(String discriminator, long bucket) {
        return Objects.hash(discriminator, bucket, 17L, 97L);
    }

    private record AuthBotProfile(String key, String displayName, String email, Set<AppRole> roles, int rankPoints, int xp, int coins) {
    }

    private record GuestBotProfile(String key, String displayName, int rankPoints, int xp, int coins) {
    }

    private record QuizBundle(List<QuizQuestion> questions, Map<Long, List<QuizAnswerOption>> optionsByQuestionId) {
    }

    private record ManagedLobbyBlueprint(
            String code,
            boolean ownerAuthenticated,
            List<DemoSessionSlot> pool,
            int maxPlayers,
            int minDesiredPlayers,
            int maxDesiredPlayers,
            boolean live,
            int createdMinutesAgo
    ) {
        DemoSessionSlot owner() {
            return pool.get(0);
        }
    }

    private record DemoSessionSlot(String key, String displayName, String sessionId, AppUser user, GuestSession session) {
        double skill() {
            int rankPoints = user == null ? session.getRankPoints() : user.getRankPoints();
            return Math.max(0.36, Math.min(0.9, 0.42 + (rankPoints / 2600.0)));
        }
    }

    private record DemoRoster(List<DemoSessionSlot> authenticated, List<DemoSessionSlot> guests) {
        boolean isEmpty() {
            return authenticated.isEmpty() && guests.isEmpty();
        }

        List<DemoSessionSlot> allSlots() {
            List<DemoSessionSlot> all = new ArrayList<>(authenticated.size() + guests.size());
            all.addAll(authenticated);
            all.addAll(guests);
            return all;
        }

        Set<String> allSessionIds() {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (DemoSessionSlot slot : allSlots()) {
                out.add(slot.sessionId());
            }
            return out;
        }
    }
}
