package pl.mindrush.backend.game;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.achievement.UserAchievementService;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.casual.CasualThreeLivesRecordService;
import pl.mindrush.backend.game.dto.GameOptionDto;
import pl.mindrush.backend.game.dto.GamePlayerDto;
import pl.mindrush.backend.game.dto.GameQuestionDto;
import pl.mindrush.backend.game.dto.GameStateDto;
import pl.mindrush.backend.game.dto.ActiveGameDto;
import pl.mindrush.backend.game.events.GameEventPublisher;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionRepository;
import pl.mindrush.backend.guest.GuestSessionService;
import pl.mindrush.backend.lobby.*;
import pl.mindrush.backend.lobby.chat.LobbySystemMessageService;
import pl.mindrush.backend.quiz.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.springframework.http.HttpStatus.*;

@Service
@Transactional
public class GameService {

    private static final int THREE_LIVES_MAX_WRONG = 3;
    private static final String SOLO_SESSION_LOBBY_ID_PREFIX = "SOLO";
    private static final List<LobbyStatus> ACTIVE_LOBBY_STATUSES = List.of(LobbyStatus.OPEN, LobbyStatus.IN_GAME);
    // Training mode has no per-question timer, but DB schema may require non-null stage_ends_at.
    // Keep a far-future placeholder in persistence and hide it in API responses.
    private static final Duration NO_TIMER_STAGE_ENDS_AT_PLACEHOLDER = Duration.ofDays(365);
    private static final Duration DEFAULT_TRAINING_INACTIVITY_TIMEOUT = Duration.ofHours(2);
    private static final Duration TRAINING_ACTIVITY_TOUCH_DEBOUNCE = Duration.ofSeconds(10);

    private final Clock clock;
    private final Duration guestQuestionDuration;
    private final Duration revealDuration;
    private final Duration preCountdownDuration;
    private final Duration finalRevealDuration;
    private final Duration trainingInactivityTimeout;

    private final GuestSessionService guestSessionService;
    private final LobbyRepository lobbyRepository;
    private final LobbyParticipantRepository participantRepository;
    private final QuizRepository quizRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAnswerOptionRepository optionRepository;
    private final GameSessionRepository gameSessionRepository;
    private final GameSessionQuestionRepository gameSessionQuestionRepository;
    private final GameAnswerRepository gameAnswerRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final GameEventPublisher gameEventPublisher;
    private final GuestSessionRepository guestSessionRepository;
    private final AppUserRepository appUserRepository;
    private final LobbySystemMessageService lobbySystemMessageService;
    private final CasualThreeLivesRecordService casualThreeLivesRecordService;
    private final UserAchievementService userAchievementService;

    public GameService(
            Clock clock,
            @Value("${game.guest.question-duration:PT10S}") Duration guestQuestionDuration,
            @Value("${game.guest.reveal-duration:PT3S}") Duration revealDuration,
            @Value("${game.guest.pre-countdown-duration:PT4S}") Duration preCountdownDuration,
            @Value("${game.guest.final-reveal-duration:PT2S}") Duration finalRevealDuration,
            @Value("${game.training.inactivity-timeout:PT2H}") Duration trainingInactivityTimeout,
            GuestSessionService guestSessionService,
            LobbyRepository lobbyRepository,
            LobbyParticipantRepository participantRepository,
            QuizRepository quizRepository,
            QuizQuestionRepository questionRepository,
            QuizAnswerOptionRepository optionRepository,
            GameSessionRepository gameSessionRepository,
            GameSessionQuestionRepository gameSessionQuestionRepository,
            GameAnswerRepository gameAnswerRepository,
            GamePlayerRepository gamePlayerRepository,
            GameEventPublisher gameEventPublisher,
            GuestSessionRepository guestSessionRepository,
            AppUserRepository appUserRepository,
            LobbySystemMessageService lobbySystemMessageService,
            CasualThreeLivesRecordService casualThreeLivesRecordService,
            UserAchievementService userAchievementService
    ) {
        this.clock = clock;
        this.guestQuestionDuration = guestQuestionDuration;
        this.revealDuration = revealDuration;
        this.preCountdownDuration = preCountdownDuration;
        this.finalRevealDuration = finalRevealDuration;
        this.trainingInactivityTimeout = normalizeTrainingInactivityTimeout(trainingInactivityTimeout);
        this.guestSessionService = guestSessionService;
        this.lobbyRepository = lobbyRepository;
        this.participantRepository = participantRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.gameSessionRepository = gameSessionRepository;
        this.gameSessionQuestionRepository = gameSessionQuestionRepository;
        this.gameAnswerRepository = gameAnswerRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.gameEventPublisher = gameEventPublisher;
        this.guestSessionRepository = guestSessionRepository;
        this.appUserRepository = appUserRepository;
        this.lobbySystemMessageService = lobbySystemMessageService;
        this.casualThreeLivesRecordService = casualThreeLivesRecordService;
        this.userAchievementService = userAchievementService;
    }

    private Duration normalizeTrainingInactivityTimeout(Duration raw) {
        if (raw == null || raw.isNegative() || raw.isZero()) {
            return DEFAULT_TRAINING_INACTIVITY_TIMEOUT;
        }
        return raw;
    }

    private long questionDurationMs() {
        long ms = guestQuestionDuration.toMillis();
        return ms <= 0 ? 10_000L : ms;
    }

    private long questionDurationMs(GameSession session) {
        if (!hasQuestionTimer(session)) return 0L;
        Integer ms = session.getQuestionDurationMs();
        if (ms == null) return questionDurationMs();
        return ms <= 0 ? questionDurationMs() : ms;
    }

    private GameSessionMode sessionMode(GameSession session) {
        return session == null ? GameSessionMode.STANDARD : session.getMode();
    }

    private boolean isThreeLives(GameSession session) {
        return sessionMode(session) == GameSessionMode.THREE_LIVES;
    }

    private boolean isTraining(GameSession session) {
        return sessionMode(session) == GameSessionMode.TRAINING;
    }

    private boolean hasQuestionTimer(GameSession session) {
        return !isTraining(session);
    }

    private boolean isQuizPlayableForUser(Quiz quiz, Long userId) {
        return QuizVisibilityRules.canUserPlay(quiz, userId);
    }

    private boolean isLobbyQuizPlayable(Quiz quiz) {
        return QuizVisibilityRules.isPubliclyVisible(quiz);
    }

    private boolean normalizeRankingRequested(Boolean ranked, boolean fallback) {
        return ranked == null ? fallback : ranked;
    }

    private void validateRankingConfig(GameSessionMode mode, boolean rankingEnabled, Quiz quiz) {
        if (!rankingEnabled) return;
        if (mode != GameSessionMode.STANDARD) {
            throw new ResponseStatusException(CONFLICT, "Ranked points are available only in standard mode");
        }
        if (quiz == null || !quiz.isIncludeInRanking()) {
            throw new ResponseStatusException(CONFLICT, "Selected quiz is not eligible for ranked games");
        }
        if (quiz.getSource() != QuizSource.OFFICIAL) {
            throw new ResponseStatusException(CONFLICT, "Ranked games support official quizzes only");
        }
    }

    private int clampInt(long v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return (int) v;
    }

    private int computeAnswerTimeMs(GameSession session, Instant answeredAt) {
        if (!hasQuestionTimer(session)) return 0;

        if (session.getStage() != GameStage.QUESTION || session.getStageEndsAt() == null) {
            return (int) questionDurationMs(session);
        }

        Instant stageStart = session.getStageEndsAt().minus(Duration.ofMillis(questionDurationMs(session)));
        long ms = Duration.between(stageStart, answeredAt).toMillis();
        return clampInt(ms, 0, (int) questionDurationMs(session));
    }

    private int computePoints(boolean correct, int answerTimeMs, long durationMs) {
        if (!correct) return 0;

        if (durationMs <= 0) return 100;

        double ratio = 1.0 - (Math.min(Math.max(answerTimeMs, 0), durationMs) / (double) durationMs);
        int bonus = (int) Math.round(50.0 * ratio);
        bonus = Math.max(0, Math.min(50, bonus));
        return 100 + bonus;
    }

    private static List<QuizAnswerOption> shuffledOptions(String gameSessionId, String guestSessionId, Long questionId, List<QuizAnswerOption> options) {
        long seed = mixSeed(gameSessionId, guestSessionId, questionId);
        Random rng = new Random(seed);

        List<QuizAnswerOption> copy = new ArrayList<>(options);
        for (int i = copy.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            QuizAnswerOption tmp = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, tmp);
        }
        return copy;
    }

    private static long mixSeed(String gameSessionId, String guestSessionId, Long questionId) {
        long a = gameSessionId.hashCode();
        long b = guestSessionId.hashCode();
        long c = questionId == null ? 0L : questionId;
        long seed = (a << 32) ^ b ^ c;
        seed ^= (seed >>> 33);
        seed *= 0xff51afd7ed558ccdL;
        seed ^= (seed >>> 33);
        seed *= 0xc4ceb9fe1a85ec53L;
        seed ^= (seed >>> 33);
        return seed;
    }

    public GameStateDto startGame(
            HttpServletRequest request,
            String lobbyCode,
            Long quizId,
            GameSessionMode mode,
            Boolean ranked
    ) {
        if (quizId == null) {
            throw new ResponseStatusException(CONFLICT, "quizId is required");
        }

        GameSessionMode requestedMode = mode == null ? GameSessionMode.STANDARD : mode;

        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);

        if (!Objects.equals(lobby.getOwnerGuestSessionId(), guestSession.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can start the game");
        }

        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not open");
        }

        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        if (!isLobbyQuizPlayable(quiz)) {
            throw new ResponseStatusException(CONFLICT, "Quiz is not active");
        }

        long questionCount = questionRepository.countByQuizId(quizId);
        if (questionCount == 0) {
            throw new ResponseStatusException(CONFLICT, "Quiz has no questions");
        }

        boolean rankingEnabled = normalizeRankingRequested(ranked, lobby.isRankingEnabled());
        validateRankingConfig(requestedMode, rankingEnabled, quiz);

        if (requestedMode != GameSessionMode.STANDARD) {
            long playersCount = participantRepository.countByLobbyId(lobby.getId());
            if (playersCount != 1) {
                throw new ResponseStatusException(CONFLICT, "This mode is available only for solo lobbies");
            }
        }

        Optional<GameSession> ownerActive = findActiveGameSessionForGuestSession(guestSession.getId());
        if (ownerActive.isPresent() && !Objects.equals(ownerActive.get().getLobbyId(), lobby.getId())) {
            throw new ResponseStatusException(CONFLICT, "You already have an active game in progress. Finish it before starting another one.");
        }

        Optional<GameSession> existing = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS);
        if (existing.isPresent()) {
            throw new ResponseStatusException(CONFLICT, "Game is already in progress");
        }

        return startGameInternal(lobby, quiz, requestedMode, rankingEnabled, guestSession.getId());
    }

    public GameStateDto startSoloGame(
            HttpServletRequest request,
            Long quizId,
            GameSessionMode mode,
            Boolean ranked
    ) {
        if (quizId == null) {
            throw new ResponseStatusException(CONFLICT, "quizId is required");
        }

        GameSessionMode requestedMode = mode == null ? GameSessionMode.STANDARD : mode;
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Optional<Lobby> activeLobby = findActiveLobbyForGuestSession(guestSession.getId());
        if (activeLobby.isPresent()) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "You are already in lobby " + activeLobby.get().getCode() + ". Leave the lobby before starting a solo game."
            );
        }

        if (findActiveGameSessionForGuestSession(guestSession.getId()).isPresent()) {
            throw new ResponseStatusException(CONFLICT, "You already have an active game in progress. Finish it before starting another one.");
        }

        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        if (!isQuizPlayableForUser(quiz, guestSession.getUserId())) {
            throw new ResponseStatusException(CONFLICT, "Quiz is not active");
        }

        long questionCount = questionRepository.countByQuizId(quizId);
        if (questionCount == 0) {
            throw new ResponseStatusException(CONFLICT, "Quiz has no questions");
        }

        boolean rankingEnabled = normalizeRankingRequested(ranked, false);
        validateRankingConfig(requestedMode, rankingEnabled, quiz);

        String displayName = Optional.ofNullable(guestSession.getDisplayName())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse("Player");

        return startSoloGameInternal(quiz, requestedMode, rankingEnabled, guestSession.getId(), displayName);
    }

    public Optional<GameStateDto> tryStartGameFromReady(String lobbyCode) {
        Lobby lobby = lobbyRepository.findByCode(lobbyCode).orElse(null);
        if (lobby == null) return Optional.empty();
        if (lobby.getStatus() != LobbyStatus.OPEN) return Optional.empty();
        if (lobby.getSelectedQuizId() == null) return Optional.empty();

        List<LobbyParticipant> lobbyPlayers = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
        if (lobbyPlayers.size() < 2) return Optional.empty();
        boolean allReady = lobbyPlayers.stream().allMatch(LobbyParticipant::isReady);
        if (!allReady) return Optional.empty();

        Optional<GameSession> existing = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS);
        if (existing.isPresent()) return Optional.empty();
        Optional<GameSession> ownerActive = findActiveGameSessionForGuestSession(lobby.getOwnerGuestSessionId());
        if (ownerActive.isPresent() && !Objects.equals(ownerActive.get().getLobbyId(), lobby.getId())) {
            return Optional.empty();
        }

        Quiz quiz = quizRepository.findById(lobby.getSelectedQuizId()).orElse(null);
        if (!isLobbyQuizPlayable(quiz)) return Optional.empty();
        long questionCount = questionRepository.countByQuizId(quiz.getId());
        if (questionCount == 0) return Optional.empty();

        boolean rankingEnabled = lobby.isRankingEnabled();
        if (rankingEnabled && !quiz.isIncludeInRanking()) return Optional.empty();
        return Optional.of(startGameInternal(
                lobby,
                quiz,
                GameSessionMode.STANDARD,
                rankingEnabled,
                lobby.getOwnerGuestSessionId()
        ));
    }

    public GameStateDto getState(HttpServletRequest request, String lobbyCode) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);
        requireParticipant(lobby.getId(), guestSession.getId());

        Optional<GameSession> sessionOpt = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS);
        if (sessionOpt.isEmpty()) {
            Optional<GameSession> last = gameSessionRepository.findFirstByLobbyIdOrderByStartedAtDesc(lobby.getId());
            if (last.isEmpty() || last.get().getStatus() != GameStatus.FINISHED) {
                return new GameStateDto(
                        lobby.getCode(),
                        lobby.getStatus().name(),
                        "NONE",
                        "STANDARD",
                        0,
                        0,
                        "NO_GAME",
                        clock.instant().toString(),
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null
                );
            }
            requireGamePlayer(last.get().getId(), guestSession.getId());
            return buildState(lobby.getCode(), lobby.getStatus().name(), last.get(), guestSession.getId());
        }

        GameSession session = sessionOpt.get();
        requireGamePlayer(session.getId(), guestSession.getId());
        if (expireTrainingSessionIfIdle(session, clock.instant())) {
            return buildState(lobby.getCode(), lobby.getStatus().name(), session, guestSession.getId());
        }

        touchTrainingActivity(session);
        boolean advanced = advanceIfNeeded(lobby, session);
        if (advanced) {
            gameEventPublisher.gameUpdated(lobby.getCode());
        }
        return buildState(lobby.getCode(), lobby.getStatus().name(), session, guestSession.getId());
    }

    public GameStateDto getSoloState(HttpServletRequest request, String gameSessionId) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        GameSession session = requireSoloSession(gameSessionId);
        requireGamePlayer(session.getId(), guestSession.getId());
        if (expireTrainingSessionIfIdle(session, clock.instant())) {
            return buildState("SOLO", soloLobbyStatus(session), session, guestSession.getId());
        }

        touchTrainingActivity(session);
        advanceIfNeededSolo(session);
        return buildState("SOLO", soloLobbyStatus(session), session, guestSession.getId());
    }

    public GameStateDto submitAnswer(HttpServletRequest request, String lobbyCode, Long questionId, Long optionId) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);
        requireParticipant(lobby.getId(), guestSession.getId());

        GameSession session = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS).orElseThrow(() -> new ResponseStatusException(CONFLICT, "No active game"));
        requireGamePlayer(session.getId(), guestSession.getId());
        if (expireTrainingSessionIfIdle(session, clock.instant())) {
            return buildState(lobby.getCode(), lobby.getStatus().name(), session, guestSession.getId());
        }

        touchTrainingActivity(session);
        advanceIfNeeded(lobby, session);

        if (lobby.getStatus() != LobbyStatus.IN_GAME) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not in game");
        }
        if (session.getStage() != GameStage.QUESTION) {
            throw new ResponseStatusException(CONFLICT, "Not accepting answers at this stage");
        }

        CurrentQuestion current = currentQuestion(session);

        if (questionId == null || optionId == null) {
            throw new ResponseStatusException(CONFLICT, "questionId and optionId are required");
        }
        if (!Objects.equals(questionId, current.sessionQuestionId())) {
            throw new ResponseStatusException(CONFLICT, "Not the current question");
        }

        if (gameAnswerRepository.existsByGameSessionIdAndQuestionIdAndGuestSessionId(
                session.getId(),
                current.sessionQuestionId(),
                guestSession.getId()
        )) {
            return buildState(lobby.getCode(), lobby.getStatus().name(), session, guestSession.getId());
        }

        QuizAnswerOption selected = current.options().stream().filter(o -> Objects.equals(o.getId(), optionId)).findFirst().orElseThrow(() -> new ResponseStatusException(CONFLICT, "Invalid optionId"));

        boolean correct = selected.isCorrect();
        Instant now = clock.instant();
        int answerTimeMs = computeAnswerTimeMs(session, now);
        int points = computePoints(correct, answerTimeMs, questionDurationMs(session));
        try {
            gameAnswerRepository.save(GameAnswer.create(
                    session,
                    current.sessionQuestionId(),
                    guestSession.getId(),
                    optionId,
                    correct,
                    answerTimeMs,
                    points,
                    now
            ));
        } catch (DataIntegrityViolationException ignored) {
        }

        advanceIfNeeded(lobby, session);
        gameEventPublisher.gameUpdated(lobby.getCode());
        return buildState(lobby.getCode(), lobby.getStatus().name(), session, guestSession.getId());
    }

    public GameStateDto submitSoloAnswer(
            HttpServletRequest request,
            String gameSessionId,
            Long questionId,
            Long optionId
    ) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        GameSession session = requireSoloSession(gameSessionId);
        requireGamePlayer(session.getId(), guestSession.getId());
        if (expireTrainingSessionIfIdle(session, clock.instant())) {
            return buildState("SOLO", soloLobbyStatus(session), session, guestSession.getId());
        }

        touchTrainingActivity(session);
        advanceIfNeededSolo(session);

        if (session.getStatus() != GameStatus.IN_PROGRESS) {
            return buildState("SOLO", soloLobbyStatus(session), session, guestSession.getId());
        }
        if (session.getStage() != GameStage.QUESTION) {
            throw new ResponseStatusException(CONFLICT, "Not accepting answers at this stage");
        }

        CurrentQuestion current = currentQuestion(session);

        if (questionId == null || optionId == null) {
            throw new ResponseStatusException(CONFLICT, "questionId and optionId are required");
        }
        if (!Objects.equals(questionId, current.sessionQuestionId())) {
            throw new ResponseStatusException(CONFLICT, "Not the current question");
        }

        if (gameAnswerRepository.existsByGameSessionIdAndQuestionIdAndGuestSessionId(
                session.getId(),
                current.sessionQuestionId(),
                guestSession.getId()
        )) {
            return buildState("SOLO", soloLobbyStatus(session), session, guestSession.getId());
        }

        QuizAnswerOption selected = current.options().stream()
                .filter(o -> Objects.equals(o.getId(), optionId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Invalid optionId"));

        boolean correct = selected.isCorrect();
        Instant now = clock.instant();
        int answerTimeMs = computeAnswerTimeMs(session, now);
        int points = computePoints(correct, answerTimeMs, questionDurationMs(session));
        try {
            gameAnswerRepository.save(GameAnswer.create(
                    session,
                    current.sessionQuestionId(),
                    guestSession.getId(),
                    optionId,
                    correct,
                    answerTimeMs,
                    points,
                    now
            ));
        } catch (DataIntegrityViolationException ignored) {
        }

        advanceIfNeededSolo(session);
        return buildState("SOLO", soloLobbyStatus(session), session, guestSession.getId());
    }

    public GameStateDto endGame(HttpServletRequest request, String lobbyCode) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);

        if (!Objects.equals(lobby.getOwnerGuestSessionId(), guestSession.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can end the game");
        }

        GameSession session = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS)
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "No active game"));
        requireGamePlayer(session.getId(), guestSession.getId());

        finishGame(lobby, session, GameFinishReason.MANUAL_END);
        gameEventPublisher.gameUpdated(lobby.getCode());
        return buildState(lobby.getCode(), lobby.getStatus().name(), session, guestSession.getId());
    }

    public GameStateDto endSoloGame(HttpServletRequest request, String gameSessionId) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        GameSession session = requireSoloSession(gameSessionId);
        requireGamePlayer(session.getId(), guestSession.getId());

        finishSoloGame(session, GameFinishReason.MANUAL_END);
        return buildState("SOLO", soloLobbyStatus(session), session, guestSession.getId());
    }

    private void finishGame(Lobby lobby, GameSession session) {
        finishGame(lobby, session, GameFinishReason.COMPLETED);
    }

    private void finishGame(Lobby lobby, GameSession session, GameFinishReason finishReason) {
        Instant now = clock.instant();
        if (session.getStatus() == GameStatus.FINISHED) return;

        List<GamePlayer> players = gamePlayerRepository.findAllByGameSessionIdOrderByOrderIndexAsc(session.getId());

        // If an admin ends the game mid-question, treat missing answers as timeouts for the current question
        // to keep scoring consistent.
        if (session.getStage() == GameStage.QUESTION) {
            try {
                CurrentQuestion current = currentQuestion(session);
                ensureTimeoutAnswers(session, current.sessionQuestionId(), now);
            } catch (Exception ignored) {
            }
        }

        session.setStatus(GameStatus.FINISHED);
        session.setEndedAt(now);
        session.setFinishReason(finishReason == null ? GameFinishReason.COMPLETED : finishReason);
        gameSessionRepository.save(session);

        applyRewardsIfNeeded(session, players, now);
        updateThreeLivesRecordIfNeeded(session, players, now);
        refreshAchievementsForFinishedPlayers(players, now);
        publishFinalWinnerMessage(lobby, session, players);

        lobby.setStatus(LobbyStatus.OPEN);
        lobbyRepository.save(lobby);
    }

    private void applyRewardsIfNeeded(GameSession session, List<GamePlayer> players, Instant now) {
        if (session.isRewardsApplied()) return;

        Quiz quiz = quizRepository.findById(session.getQuizId()).orElse(null);
        RewardsPolicy rewardsPolicy = rewardsPolicyForExistingSession(session, quiz);

        if (!rewardsPolicy.xpEnabled() && !rewardsPolicy.coinsEnabled() && !rewardsPolicy.rankPointsEnabled()) {
            session.setRewardsApplied(true);
            session.setRewardsAppliedAt(now);
            gameSessionRepository.save(session);
            return;
        }

        Map<String, PlayerTotals> totalsByGuest = computeTotalsByGuest(session.getId());
        List<PlayerStanding> standings = computeStandings(players, totalsByGuest);
        Map<String, PlayerRewards> rewards = computeRewards(standings);

        for (GamePlayer p : players) {
            PlayerRewards r = rewards.get(p.getGuestSessionId());
            if (r == null) continue;
            int xp = rewardsPolicy.xpEnabled() ? r.xpDelta() : 0;
            int rp = rewardsPolicy.rankPointsEnabled() ? r.rankPointsDelta() : 0;
            int coins = rewardsPolicy.coinsEnabled() ? r.coinsDelta() : 0;
            applyRewardsToPlayer(p.getGuestSessionId(), xp, rp, coins);
        }

        session.setRewardsApplied(true);
        session.setRewardsAppliedAt(now);
        gameSessionRepository.save(session);
    }

    private RewardsPolicy rewardsPolicyForSession(
            GameSessionMode mode,
            boolean rankingEnabled,
            Quiz quiz
    ) {
        boolean rewardsEnabled = mode != GameSessionMode.TRAINING;
        boolean xpEnabled = rewardsEnabled && (quiz == null || quiz.isXpEnabled());
        boolean coinsEnabled = rewardsEnabled;
        boolean rankPointsEnabled = rewardsEnabled
                && mode == GameSessionMode.STANDARD
                && rankingEnabled;
        return new RewardsPolicy(xpEnabled, coinsEnabled, rankPointsEnabled);
    }

    private RewardsPolicy rewardsPolicyForExistingSession(GameSession session, Quiz quiz) {
        GameSessionMode mode = sessionMode(session);
        RewardsPolicy fallback = rewardsPolicyForSession(
                mode,
                mode == GameSessionMode.STANDARD && quiz != null && quiz.isIncludeInRanking(),
                quiz
        );
        boolean xpEnabled = session.getXpRewardsEnabledRaw() == null
                ? fallback.xpEnabled()
                : session.isXpRewardsEnabled();
        boolean coinsEnabled = session.getCoinsRewardsEnabledRaw() == null
                ? fallback.coinsEnabled()
                : session.isCoinsRewardsEnabled();
        boolean rankPointsEnabled = session.getRankPointsRewardsEnabledRaw() == null
                ? fallback.rankPointsEnabled()
                : session.isRankPointsRewardsEnabled();
        return new RewardsPolicy(xpEnabled, coinsEnabled, rankPointsEnabled);
    }

    private void updateThreeLivesRecordIfNeeded(GameSession session, List<GamePlayer> players, Instant now) {
        if (!isThreeLives(session)) return;
        if (players == null || players.isEmpty()) return;
        GamePlayer soloPlayer = players.get(0);
        if (soloPlayer == null || soloPlayer.getGuestSessionId() == null) return;

        PlayerTotals totals = computeTotalsByGuest(session.getId())
                .getOrDefault(soloPlayer.getGuestSessionId(), PlayerTotals.empty());
        long answered = gameAnswerRepository.countByGameSessionIdAndGuestSessionId(session.getId(), soloPlayer.getGuestSessionId());
        long durationMs = session.getStartedAt() == null
                ? 0L
                : Math.max(0L, Duration.between(session.getStartedAt(), now).toMillis());

        casualThreeLivesRecordService.updateBestForGuestSession(
                soloPlayer.getGuestSessionId(),
                (int) Math.max(0L, totals.totalPoints()),
                (int) Math.max(0L, answered),
                durationMs
        );
    }

    private void applyRewardsToPlayer(String guestSessionId, int xpDelta, int rankPointsDelta, int coinsDelta) {
        guestSessionRepository.findById(guestSessionId).ifPresent(gs -> {
            Long userId = gs.getUserId();
            if (userId != null) {
                appUserRepository.findById(userId).ifPresent(u -> {
                    u.setXp(u.getXp() + xpDelta);
                    u.setRankPoints(u.getRankPoints() + rankPointsDelta);
                    u.setCoins(u.getCoins() + coinsDelta);
                    appUserRepository.save(u);
                });
                return;
            }

            gs.setXp(gs.getXp() + xpDelta);
            gs.setRankPoints(gs.getRankPoints() + rankPointsDelta);
            gs.setCoins(gs.getCoins() + coinsDelta);
            guestSessionRepository.save(gs);
        });
    }

    public void tickDueSessions() {
        Instant now = clock.instant();
        expireStaleTrainingSessions(now);

        List<GameSession> due = gameSessionRepository.findAllByStatusAndStageEndsAtBefore(GameStatus.IN_PROGRESS, now);
        for (GameSession session : due) {
            if (isSoloSession(session)) {
                advanceIfNeededSolo(session);
                continue;
            }
            Lobby lobby = lobbyRepository.findById(session.getLobbyId()).orElse(null);
            if (lobby == null) continue;
            boolean changed = advanceIfNeeded(lobby, session);
            if (changed) {
                gameEventPublisher.gameUpdated(lobby.getCode());
            }
        }
    }

    private void expireStaleTrainingSessions(Instant now) {
        Instant cutoff = now.minus(trainingInactivityTimeout);
        List<GameSession> staleTraining = gameSessionRepository.findStaleSessionsByModeAndStatus(
                GameSessionMode.TRAINING,
                GameStatus.IN_PROGRESS,
                cutoff
        );
        for (GameSession session : staleTraining) {
            expireTrainingSessionIfIdle(session, now);
        }
    }

    private boolean expireTrainingSessionIfIdle(GameSession session, Instant now) {
        if (!shouldExpireTrainingSession(session, now)) return false;

        if (isSoloSession(session)) {
            finishSoloGame(session, GameFinishReason.EXPIRED);
            return true;
        }

        Lobby lobby = lobbyRepository.findById(session.getLobbyId()).orElse(null);
        if (lobby == null) {
            finishSessionWithoutLobby(session, now, GameFinishReason.EXPIRED);
            return true;
        }

        finishGame(lobby, session, GameFinishReason.EXPIRED);
        gameEventPublisher.gameUpdated(lobby.getCode());
        return true;
    }

    private void finishSessionWithoutLobby(GameSession session, Instant now, GameFinishReason finishReason) {
        if (session == null || session.getStatus() == GameStatus.FINISHED) return;
        session.setStatus(GameStatus.FINISHED);
        session.setEndedAt(now);
        session.setFinishReason(finishReason == null ? GameFinishReason.EXPIRED : finishReason);
        gameSessionRepository.save(session);
    }

    private boolean shouldExpireTrainingSession(GameSession session, Instant now) {
        if (session == null) return false;
        if (session.getStatus() != GameStatus.IN_PROGRESS) return false;
        if (!isTraining(session)) return false;

        Instant lastActivity = effectiveLastActivityAt(session);
        if (lastActivity == null) return false;
        return !now.isBefore(lastActivity.plus(trainingInactivityTimeout));
    }

    private Instant effectiveLastActivityAt(GameSession session) {
        if (session == null) return null;
        Instant lastActivity = session.getLastActivityAt();
        return lastActivity != null ? lastActivity : session.getStartedAt();
    }

    private void touchTrainingActivity(GameSession session) {
        if (session == null) return;
        if (session.getStatus() != GameStatus.IN_PROGRESS) return;
        if (!isTraining(session)) return;

        Instant now = clock.instant();
        Instant lastActivity = effectiveLastActivityAt(session);
        if (lastActivity != null && now.isBefore(lastActivity.plus(TRAINING_ACTIVITY_TOUCH_DEBOUNCE))) {
            return;
        }

        session.setLastActivityAt(now);
        gameSessionRepository.save(session);
    }

    private GameStateDto buildState(
            String lobbyCode,
            String lobbyStatus,
            GameSession session,
            String viewerGuestSessionId
    ) {
        String serverTime = clock.instant().toString();
        String responseStageEndsAt = stageEndsAtForResponse(session);
        List<GamePlayer> players = gamePlayerRepository.findAllByGameSessionIdOrderByOrderIndexAsc(session.getId());
        Map<String, PlayerTotals> totalsByGuest = computeTotalsByGuest(session.getId());
        Map<String, Boolean> authenticatedByGuestSessionId = resolveAuthenticatedByGuestSessionId(players);
        Map<String, Boolean> premiumByGuestSessionId = resolvePremiumByGuestSessionId(players);
        Map<String, Integer> rankPointsByGuestSessionId = resolveRankPointsByGuestSessionId(players);
        GameSessionMode mode = sessionMode(session);
        long totalQuestions = countSessionQuestionPool(session);
        int responseTotalQuestions = (int) totalQuestions;
        Integer wrongAnswers = threeLivesWrongAnswers(session, players);
        Integer livesRemaining = threeLivesLivesRemaining(wrongAnswers);

        int questionIndex = session.getCurrentQuestionIndex();
        if (session.getStatus() == GameStatus.FINISHED || questionIndex >= totalQuestions) {
            List<PlayerStanding> standings = computeStandings(players, totalsByGuest);
            Map<String, PlayerRewards> rewards = computeRewards(standings);

            Quiz quiz = quizRepository.findById(session.getQuizId()).orElse(null);
            RewardsPolicy rewardsPolicy = rewardsPolicyForExistingSession(session, quiz);

            List<GamePlayerDto> playersDto = players.stream()
                    .map(p -> {
                        PlayerTotals t = totalsByGuest.getOrDefault(p.getGuestSessionId(), PlayerTotals.empty());
                        PlayerRewards r = rewards.get(p.getGuestSessionId());
                        return new GamePlayerDto(
                                p.getDisplayName(),
                                authenticatedByGuestSessionId.getOrDefault(p.getGuestSessionId(), false),
                                premiumByGuestSessionId.getOrDefault(p.getGuestSessionId(), false),
                                false,
                                null,
                                t.totalPoints(),
                                t.correctAnswers(),
                                t.totalAnswerTimeMs(),
                                t.totalCorrectAnswerTimeMs(),
                                r == null || !rewardsPolicy.xpEnabled() ? null : r.xpDelta(),
                                r == null || !rewardsPolicy.coinsEnabled() ? null : r.coinsDelta(),
                                r == null || !rewardsPolicy.rankPointsEnabled() ? null : r.rankPointsDelta(),
                                rankPointsByGuestSessionId.get(p.getGuestSessionId()),
                                r == null ? null : r.winner()
                        );
                    })
                    .toList();

            return new GameStateDto(
                    lobbyCode,
                    lobbyStatus,
                    session.getStatus().name(),
                    mode.name(),
                    (int) Math.min(questionIndex + 1L, totalQuestions),
                    responseTotalQuestions,
                    "FINISHED",
                    serverTime,
                    null,
                    null,
                    null,
                    playersDto,
                    session.getId(),
                    null,
                    livesRemaining,
                    wrongAnswers,
                    session.getFinishReason() == null ? null : session.getFinishReason().name()
            );
        }

        if (session.getStage() == GameStage.PRE_COUNTDOWN) {
            List<GamePlayerDto> playersDto = players.stream()
                    .map(p -> {
                        PlayerTotals t = totalsByGuest.getOrDefault(p.getGuestSessionId(), PlayerTotals.empty());
                        return new GamePlayerDto(
                                p.getDisplayName(),
                                authenticatedByGuestSessionId.getOrDefault(p.getGuestSessionId(), false),
                                premiumByGuestSessionId.getOrDefault(p.getGuestSessionId(), false),
                                false,
                                null,
                                t.totalPoints(),
                                t.correctAnswers(),
                                t.totalAnswerTimeMs(),
                                t.totalCorrectAnswerTimeMs(),
                                null,
                                null,
                                null,
                                rankPointsByGuestSessionId.get(p.getGuestSessionId()),
                                null
                        );
                    })
                    .toList();

            return new GameStateDto(
                    lobbyCode,
                    lobbyStatus,
                    session.getStatus().name(),
                    mode.name(),
                    1,
                    responseTotalQuestions,
                    session.getStage().name(),
                    serverTime,
                    session.getStageEndsAt() == null ? null : session.getStageEndsAt().toString(),
                    preCountdownDuration.toMillis(),
                    null,
                    playersDto,
                    session.getId(),
                    null,
                    livesRemaining,
                    wrongAnswers,
                    null
            );
        }

        CurrentQuestion current = currentQuestion(session);

        boolean reveal = session.getStage() == GameStage.REVEAL;

        Map<String, GameAnswer> answersByGuestSessionId = gameAnswerRepository
                .findAllByGameSessionIdAndQuestionId(session.getId(), current.sessionQuestionId())
                .stream()
                .collect(java.util.stream.Collectors.toMap(GameAnswer::getGuestSessionId, a -> a));

        List<GamePlayerDto> playersDto = players.stream().map(p -> {
            GameAnswer answer = answersByGuestSessionId.get(p.getGuestSessionId());
            boolean answered = answer != null && answer.getSelectedOptionId() != null;
            Boolean correct = reveal && answer != null ? answer.isCorrect() : null;
            PlayerTotals t = totalsByGuest.getOrDefault(p.getGuestSessionId(), PlayerTotals.empty());
            return new GamePlayerDto(
                    p.getDisplayName(),
                    authenticatedByGuestSessionId.getOrDefault(p.getGuestSessionId(), false),
                    premiumByGuestSessionId.getOrDefault(p.getGuestSessionId(), false),
                    answered,
                    correct,
                    t.totalPoints(),
                    t.correctAnswers(),
                    t.totalAnswerTimeMs(),
                    t.totalCorrectAnswerTimeMs(),
                    null,
                    null,
                    null,
                    rankPointsByGuestSessionId.get(p.getGuestSessionId()),
                    null
            );
        }).toList();

        List<GameOptionDto> orderedOptions = shuffledOptions(
                        session.getId(),
                        viewerGuestSessionId,
                        current.sessionQuestionId(),
                        current.options()
                )
                .stream()
                .map(o -> new GameOptionDto(o.getId(), o.getText(), o.getImageUrl()))
                .toList();

        GameQuestionDto questionDto = new GameQuestionDto(
                current.sessionQuestionId(),
                current.question().getPrompt(),
                current.question().getImageUrl(),
                orderedOptions
        );

        Long correctOptionId = null;
        if (reveal) {
            correctOptionId = current.options().stream()
                    .filter(QuizAnswerOption::isCorrect)
                    .map(QuizAnswerOption::getId)
                    .findFirst()
                    .orElse(null);
        }

        Long stageTotalMs;
        if (session.getStage() == GameStage.QUESTION) {
            stageTotalMs = hasQuestionTimer(session) ? questionDurationMs(session) : null;
        } else if (current.indexOneBased() >= current.totalQuestions()) {
            stageTotalMs = finalRevealDuration.toMillis();
        } else {
            stageTotalMs = revealDuration.toMillis();
        }

        return new GameStateDto(
                lobbyCode,
                lobbyStatus,
                session.getStatus().name(),
                mode.name(),
                current.indexOneBased(),
                responseTotalQuestions,
                session.getStage().name(),
                serverTime,
                responseStageEndsAt,
                stageTotalMs,
                questionDto,
                playersDto,
                session.getId(),
                correctOptionId,
                livesRemaining,
                wrongAnswers,
                null
        );
    }

    private Integer threeLivesWrongAnswers(GameSession session, List<GamePlayer> players) {
        if (!isThreeLives(session)) return null;
        if (players == null || players.isEmpty()) return 0;
        String guestSessionId = players.get(0).getGuestSessionId();
        if (guestSessionId == null || guestSessionId.isBlank()) return 0;
        return (int) gameAnswerRepository.countWrongByGameSessionIdAndGuestSessionId(session.getId(), guestSessionId);
    }

    private Integer threeLivesLivesRemaining(Integer wrongAnswers) {
        if (wrongAnswers == null) return null;
        return Math.max(0, THREE_LIVES_MAX_WRONG - Math.max(0, wrongAnswers));
    }

    private Map<String, Boolean> resolveAuthenticatedByGuestSessionId(List<GamePlayer> players) {
        if (players == null || players.isEmpty()) return Map.of();

        List<String> ids = players.stream()
                .map(GamePlayer::getGuestSessionId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();

        Map<String, Boolean> out = new HashMap<>();
        for (GuestSession session : guestSessionRepository.findAllById(ids)) {
            if (session == null || session.getId() == null) continue;
            out.put(session.getId(), session.getUserId() != null);
        }
        return out;
    }

    private Map<String, Integer> resolveRankPointsByGuestSessionId(List<GamePlayer> players) {
        if (players == null || players.isEmpty()) return Map.of();

        List<String> ids = players.stream()
                .map(GamePlayer::getGuestSessionId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();

        List<GuestSession> sessions = guestSessionRepository.findAllById(ids);
        if (sessions.isEmpty()) return Map.of();

        List<Long> userIds = sessions.stream()
                .map(GuestSession::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Integer> rankPointsByUserId = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (AppUser user : appUserRepository.findAllById(userIds)) {
                if (user == null || user.getId() == null) continue;
                rankPointsByUserId.put(user.getId(), user.getRankPoints());
            }
        }

        Map<String, Integer> out = new HashMap<>();
        for (GuestSession session : sessions) {
            if (session == null || session.getId() == null) continue;
            Long userId = session.getUserId();
            Integer rankPoints = userId == null
                    ? session.getRankPoints()
                    : rankPointsByUserId.getOrDefault(userId, session.getRankPoints());
            out.put(session.getId(), rankPoints);
        }
        return out;
    }

    private Map<String, Boolean> resolvePremiumByGuestSessionId(List<GamePlayer> players) {
        if (players == null || players.isEmpty()) return Map.of();

        List<String> ids = players.stream()
                .map(GamePlayer::getGuestSessionId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();

        List<GuestSession> sessions = guestSessionRepository.findAllById(ids);
        if (sessions.isEmpty()) return Map.of();

        List<Long> userIds = sessions.stream()
                .map(GuestSession::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Boolean> premiumByUserId = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (AppUser user : appUserRepository.findAllById(userIds)) {
                if (user == null || user.getId() == null) continue;
                premiumByUserId.put(user.getId(), user.getRoles().contains(AppRole.PREMIUM));
            }
        }

        Map<String, Boolean> out = new HashMap<>();
        for (GuestSession session : sessions) {
            if (session == null || session.getId() == null) continue;
            Long userId = session.getUserId();
            out.put(session.getId(), userId != null && premiumByUserId.getOrDefault(userId, false));
        }
        return out;
    }

    private record PlayerTotals(long totalPoints, long correctAnswers, long totalAnswerTimeMs,
                                long totalCorrectAnswerTimeMs) {
        static PlayerTotals empty() {
            return new PlayerTotals(0, 0, 0, 0);
        }
    }

    private Map<String, PlayerTotals> computeTotalsByGuest(String gameSessionId) {
        List<GameAnswer> all = gameAnswerRepository.findAllByGameSessionId(gameSessionId);
        Map<String, long[]> acc = new HashMap<>();

        for (GameAnswer a : all) {
            long[] t = acc.computeIfAbsent(a.getGuestSessionId(), k -> new long[4]);
            t[0] += Math.max(0, a.getPoints());
            if (a.isCorrect()) {
                t[1] += 1;
                t[3] += Math.max(0, a.getAnswerTimeMs());
            }
            t[2] += Math.max(0, a.getAnswerTimeMs());
        }

        Map<String, PlayerTotals> out = new HashMap<>();
        for (Map.Entry<String, long[]> e : acc.entrySet()) {
            long[] t = e.getValue();
            out.put(e.getKey(), new PlayerTotals(t[0], t[1], t[2], t[3]));
        }
        return out;
    }

    private record PlayerStanding(String guestSessionId, PlayerTotals totals, int rank) {
    }

    private List<PlayerStanding> computeStandings(List<GamePlayer> players, Map<String, PlayerTotals> totalsByGuest) {
        record SortKey(String guestSessionId, PlayerTotals totals, int orderIndex) {
        }

        List<SortKey> list = new ArrayList<>();
        for (GamePlayer p : players) {
            list.add(new SortKey(p.getGuestSessionId(), totalsByGuest.getOrDefault(p.getGuestSessionId(), PlayerTotals.empty()), p.getOrderIndex()));
        }

        list.sort((a, b) -> {
            int cmpPoints = Long.compare(b.totals().totalPoints(), a.totals().totalPoints());
            if (cmpPoints != 0) return cmpPoints;
            int cmpCorrect = Long.compare(b.totals().correctAnswers(), a.totals().correctAnswers());
            if (cmpCorrect != 0) return cmpCorrect;
            int cmpTime = Long.compare(a.totals().totalCorrectAnswerTimeMs(), b.totals().totalCorrectAnswerTimeMs());
            if (cmpTime != 0) return cmpTime;
            return Integer.compare(a.orderIndex(), b.orderIndex());
        });

        List<PlayerStanding> standings = new ArrayList<>();
        int rank = 1;
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                SortKey prev = list.get(i - 1);
                SortKey cur = list.get(i);
                boolean tied =
                        prev.totals().totalPoints() == cur.totals().totalPoints() &&
                        prev.totals().correctAnswers() == cur.totals().correctAnswers() &&
                        prev.totals().totalCorrectAnswerTimeMs() == cur.totals().totalCorrectAnswerTimeMs();
                if (!tied) rank = i + 1;
            }
            SortKey cur = list.get(i);
            standings.add(new PlayerStanding(cur.guestSessionId(), cur.totals(), rank));
        }
        return standings;
    }

    private record PlayerRewards(int xpDelta, int rankPointsDelta, int coinsDelta, boolean winner) {
    }

    private record RewardsPolicy(
            boolean xpEnabled,
            boolean coinsEnabled,
            boolean rankPointsEnabled
    ) {
    }

    private Map<String, PlayerRewards> computeRewards(List<PlayerStanding> standings) {
        int n = standings.size();
        if (n == 0) return Map.of();

        Map<String, PlayerRewards> out = new HashMap<>();

        boolean isTwoPlayer = n == 2;
        boolean drawForFirst = false;
        if (n >= 2) {
            PlayerStanding a = standings.get(0);
            PlayerStanding b = standings.get(1);
            drawForFirst = a.rank() == b.rank();
        }

        for (PlayerStanding s : standings) {
            long points = s.totals().totalPoints();
            long correct = s.totals().correctAnswers();

            int xp = (int) Math.min(50_000, 50 + (int) (points / 10) + (int) (correct * 10));
            boolean winner = s.rank() == 1 && !drawForFirst;
            if (winner) xp += 50;
            else if (drawForFirst && s.rank() == 1) xp += 20;

            int rp;
            if (n <= 1) {
                rp = 0;
            } else if (isTwoPlayer) {
                if (drawForFirst) rp = 5;
                else rp = winner ? 25 : -15;
            } else {
                // rank=1 => positive, last => negative; ties share the same rank-based reward.
                rp = (n - s.rank()) * 10 - 10;
                if (drawForFirst && s.rank() == 1) rp = 5;
            }

            int coins = (int) Math.min(50_000, Math.max(0, (int) (points / 20) + (winner ? 20 : 5)));
            out.put(s.guestSessionId(), new PlayerRewards(xp, rp, coins, winner));
        }

        return out;
    }

    private Lobby requireLobbyByCode(String code) {
        return lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));
    }

    private void requireParticipant(String lobbyId, String guestSessionId) {
        if (!participantRepository.existsByLobbyIdAndGuestSessionId(lobbyId, guestSessionId)) {
            throw new ResponseStatusException(FORBIDDEN, "Not a lobby participant");
        }
    }

    private void requireGamePlayer(String gameSessionId, String guestSessionId) {
        if (!gamePlayerRepository.existsByGameSessionIdAndGuestSessionId(gameSessionId, guestSessionId)) {
            throw new ResponseStatusException(FORBIDDEN, "Not a game participant");
        }
    }

    private Instant nextQuestionStageEndsAt(Instant now, GameSession session) {
        if (!hasQuestionTimer(session)) return now.plus(NO_TIMER_STAGE_ENDS_AT_PLACEHOLDER);
        return now.plus(Duration.ofMillis(questionDurationMs(session)));
    }

    private String stageEndsAtForResponse(GameSession session) {
        if (session.getStage() == GameStage.QUESTION && !hasQuestionTimer(session)) {
            return null;
        }
        Instant stageEndsAt = session.getStageEndsAt();
        return stageEndsAt == null ? null : stageEndsAt.toString();
    }

    private boolean advanceIfNeeded(Lobby lobby, GameSession session) {
        return advanceIfNeeded(session, () -> finishGame(lobby, session));
    }

    private boolean advanceIfNeededSolo(GameSession session) {
        return advanceIfNeeded(session, () -> finishSoloGame(session));
    }

    private boolean advanceIfNeeded(GameSession session, Runnable finishAction) {
        if (session.getStatus() != GameStatus.IN_PROGRESS) return false;

        Instant now = clock.instant();

        if (session.getStage() == GameStage.PRE_COUNTDOWN) {
            if (session.getStageEndsAt() != null && now.isBefore(session.getStageEndsAt())) return false;
            session.setStage(GameStage.QUESTION);
            session.setStageEndsAt(nextQuestionStageEndsAt(now, session));
            gameSessionRepository.save(session);
            return true;
        }

        if (session.getStage() == GameStage.QUESTION) {
            CurrentQuestion current = currentQuestion(session);
            long playerCount = gamePlayerRepository.countByGameSessionId(session.getId());
            long answered = gameAnswerRepository.countByGameSessionIdAndQuestionId(session.getId(), current.sessionQuestionId());

            boolean timeUp = hasQuestionTimer(session)
                    && session.getStageEndsAt() != null
                    && !now.isBefore(session.getStageEndsAt());
            boolean allAnswered = answered >= playerCount;

            if (timeUp) {
                ensureTimeoutAnswers(session, current.sessionQuestionId(), now);
                allAnswered = true;
            }

            if (allAnswered) {
                boolean isLastQuestion = false;
                if (isThreeLives(session)) {
                    Integer wrongAnswers = threeLivesWrongAnswers(session, gamePlayerRepository.findAllByGameSessionIdOrderByOrderIndexAsc(session.getId()));
                    Integer livesRemaining = threeLivesLivesRemaining(wrongAnswers);
                    session.setLivesRemaining(livesRemaining);
                    boolean noLivesLeft = livesRemaining != null && livesRemaining <= 0;
                    boolean reachedQuestionPoolEnd = session.getCurrentQuestionIndex() + 1 >= current.totalQuestions();
                    isLastQuestion = noLivesLeft || reachedQuestionPoolEnd;
                } else {
                    isLastQuestion = session.getCurrentQuestionIndex() + 1 >= current.totalQuestions();
                }

                session.setStage(GameStage.REVEAL);
                session.setStageEndsAt(now.plus(isLastQuestion ? finalRevealDuration : revealDuration));
                gameSessionRepository.save(session);
                return true;
            }
            return false;
        }

        if (session.getStage() == GameStage.REVEAL) {
            if (session.getStageEndsAt() != null && now.isBefore(session.getStageEndsAt())) return false;

            if (isThreeLives(session)) {
                if (session.getLivesRemaining() != null && session.getLivesRemaining() <= 0) {
                    finishAction.run();
                    return true;
                }

                int nextIndex = session.getCurrentQuestionIndex() + 1;
                long totalQuestions = countSessionQuestionPool(session);
                if (nextIndex >= totalQuestions) {
                    finishAction.run();
                    return true;
                }
                session.setCurrentQuestionIndex(nextIndex);
                session.setStage(GameStage.QUESTION);
                session.setStageEndsAt(nextQuestionStageEndsAt(now, session));
                gameSessionRepository.save(session);
                return true;
            }

            int nextIndex = session.getCurrentQuestionIndex() + 1;
            long totalQuestions = countSessionQuestionPool(session);
            if (nextIndex >= totalQuestions) {
                finishAction.run();
                return true;
            }

            session.setCurrentQuestionIndex(nextIndex);
            session.setStage(GameStage.QUESTION);
            session.setStageEndsAt(nextQuestionStageEndsAt(now, session));
            gameSessionRepository.save(session);
            return true;
        }

        return false;
    }

    private void publishFinalWinnerMessage(Lobby lobby, GameSession session, List<GamePlayer> players) {
        if (lobby == null || session == null || players == null || players.isEmpty()) return;

        Map<String, PlayerTotals> totalsByGuest = computeTotalsByGuest(session.getId());
        List<PlayerStanding> standings = computeStandings(players, totalsByGuest);
        if (standings.isEmpty()) return;

        int bestRank = standings.get(0).rank();
        LinkedHashSet<String> winners = new LinkedHashSet<>();
        for (PlayerStanding standing : standings) {
            if (standing.rank() != bestRank) continue;
            String winnerName = players.stream()
                    .filter(p -> Objects.equals(p.getGuestSessionId(), standing.guestSessionId()))
                    .map(GamePlayer::getDisplayName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse("Player");
            winners.add(winnerName);
        }

        if (winners.isEmpty()) return;
        if (winners.size() == 1) {
            String winner = winners.iterator().next();
            lobbySystemMessageService.publish(lobby.getCode(), "Game winner: " + winner + ".");
            return;
        }

        lobbySystemMessageService.publish(lobby.getCode(), "Game draw: " + String.join(", ", winners) + ".");
    }

    private void ensureTimeoutAnswers(GameSession session, Long questionId, Instant now) {
        List<GameAnswer> existing = gameAnswerRepository.findAllByGameSessionIdAndQuestionId(session.getId(), questionId);
        Set<String> answeredGuestIds = new HashSet<>();
        for (GameAnswer a : existing) {
            answeredGuestIds.add(a.getGuestSessionId());
        }

        for (GamePlayer p : gamePlayerRepository.findAllByGameSessionIdOrderByOrderIndexAsc(session.getId())) {
            if (answeredGuestIds.contains(p.getGuestSessionId())) continue;
            try {
                int answerTimeMs = (int) questionDurationMs(session);
                gameAnswerRepository.save(GameAnswer.create(session, questionId, p.getGuestSessionId(), null, false, answerTimeMs, 0, now));
            } catch (DataIntegrityViolationException ignored) {
            }
        }
    }

    private long countSessionQuestionPool(GameSession session) {
        if (session == null || session.getId() == null) return 0L;
        return gameSessionQuestionRepository.countByGameSessionId(session.getId());
    }

    private GameSessionQuestion sessionQuestionAtIndex(GameSession session, int idx) {
        return gameSessionQuestionRepository.findByGameSessionIdAndOrderIndex(session.getId(), idx)
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Question pool is out of sync"));
    }

    private Long sessionQuestionId(GameSession session, QuizQuestion question) {
        Long actualId = question.getId();
        if (actualId == null) {
            throw new ResponseStatusException(CONFLICT, "Question id is missing");
        }
        if (!isThreeLives(session)) return actualId;

        long round = Math.max(0L, session.getCurrentQuestionIndex());
        return -1L * ((round + 1L) * 1_000_000L + actualId);
    }

    private CurrentQuestion currentQuestion(GameSession session) {
        long totalLong = countSessionQuestionPool(session);
        int total = clampInt(totalLong, 0, Integer.MAX_VALUE);
        if (total <= 0) {
            throw new ResponseStatusException(CONFLICT, "Quiz has no questions");
        }

        int rawIndex = session.getCurrentQuestionIndex();
        int idx = rawIndex;
        if (idx < 0 || idx >= total) {
            throw new ResponseStatusException(CONFLICT, "Invalid question index");
        }

        GameSessionQuestion selected = sessionQuestionAtIndex(session, idx);
        QuizQuestion question = questionRepository.findById(selected.getQuestionId())
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Question not found"));
        List<QuizAnswerOption> options = optionRepository.findAllByQuestionIdOrderByOrderIndexAsc(question.getId());
        int indexOneBased = idx + 1;
        return new CurrentQuestion(
                question,
                sessionQuestionId(session, question),
                options,
                indexOneBased,
                total
        );
    }

    private record CurrentQuestion(
            QuizQuestion question,
            Long sessionQuestionId,
            List<QuizAnswerOption> options,
            int indexOneBased,
            int totalQuestions
    ) {
    }

    private void initializeSessionQuestionPool(GameSession session, Quiz quiz) {
        List<Long> allQuestionIds = questionRepository.findIdsByQuizIdOrderByOrderIndexAsc(quiz.getId());
        if (allQuestionIds.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "Quiz has no questions");
        }

        int targetCount = isThreeLives(session)
                ? allQuestionIds.size()
                : Math.min(quiz.getQuestionsPerGame(), allQuestionIds.size());
        List<Long> selectedQuestionIds = selectSessionQuestionIds(session, quiz, allQuestionIds, targetCount);
        List<GameSessionQuestion> poolRows = new ArrayList<>(selectedQuestionIds.size());
        for (int i = 0; i < selectedQuestionIds.size(); i++) {
            poolRows.add(GameSessionQuestion.create(session.getId(), selectedQuestionIds.get(i), i));
        }
        gameSessionQuestionRepository.saveAll(poolRows);
    }

    private List<Long> selectSessionQuestionIds(
            GameSession session,
            Quiz quiz,
            List<Long> allQuestionIds,
            int targetCount
    ) {
        if (targetCount <= 0) return List.of();
        long seed = mixSeed(session.getId(), "quiz:" + quiz.getId(), (long) targetCount);
        List<Long> shuffled = new ArrayList<>(allQuestionIds);
        Collections.shuffle(shuffled, new Random(seed));
        if (shuffled.size() <= targetCount) {
            return shuffled;
        }
        return new ArrayList<>(shuffled.subList(0, targetCount));
    }

    private GameStateDto startGameInternal(
            Lobby lobby,
            Quiz quiz,
            GameSessionMode mode,
            boolean rankingEnabled,
            String viewerGuestSessionId
    ) {
        Instant now = clock.instant();
        Integer qDurationMs;
        Integer quizSeconds = quiz.getQuestionTimeLimitSeconds();
        int seconds = (quizSeconds == null || quizSeconds <= 0) ? pl.mindrush.backend.quiz.Quiz.DEFAULT_QUESTION_TIME_LIMIT_SECONDS : quizSeconds;
        long computed = Math.min(Integer.MAX_VALUE, seconds * 1000L);
        qDurationMs = mode == GameSessionMode.TRAINING ? null : (int) computed;
        RewardsPolicy rewardsPolicy = rewardsPolicyForSession(mode, rankingEnabled, quiz);

        GameSession session = GameSession.startNew(
                lobby.getId(),
                quiz.getId(),
                mode,
                now,
                preCountdownDuration,
                qDurationMs,
                rewardsPolicy.xpEnabled(),
                rewardsPolicy.coinsEnabled(),
                rewardsPolicy.rankPointsEnabled()
        );
        gameSessionRepository.save(session);
        initializeSessionQuestionPool(session, quiz);

        List<LobbyParticipant> lobbyPlayers = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
        for (int i = 0; i < lobbyPlayers.size(); i++) {
            LobbyParticipant p = lobbyPlayers.get(i);
            gamePlayerRepository.save(GamePlayer.create(session, p.getGuestSessionId(), p.getDisplayName(), i + 1));
        }

        participantRepository.clearReadyByLobbyId(lobby.getId());
        lobby.setStatus(LobbyStatus.IN_GAME);
        lobbyRepository.save(lobby);

        gameEventPublisher.gameUpdated(lobby.getCode());
        return buildState(lobby.getCode(), lobby.getStatus().name(), session, viewerGuestSessionId);
    }

    private GameStateDto startSoloGameInternal(
            Quiz quiz,
            GameSessionMode mode,
            boolean rankingEnabled,
            String viewerGuestSessionId,
            String displayName
    ) {
        Instant now = clock.instant();
        Integer qDurationMs;
        Integer quizSeconds = quiz.getQuestionTimeLimitSeconds();
        int seconds = (quizSeconds == null || quizSeconds <= 0)
                ? pl.mindrush.backend.quiz.Quiz.DEFAULT_QUESTION_TIME_LIMIT_SECONDS
                : quizSeconds;
        long computed = Math.min(Integer.MAX_VALUE, seconds * 1000L);
        qDurationMs = mode == GameSessionMode.TRAINING ? null : (int) computed;
        RewardsPolicy rewardsPolicy = rewardsPolicyForSession(mode, rankingEnabled, quiz);

        GameSession session = GameSession.startNew(
                createSoloSessionLobbyId(),
                quiz.getId(),
                mode,
                now,
                preCountdownDuration,
                qDurationMs,
                rewardsPolicy.xpEnabled(),
                rewardsPolicy.coinsEnabled(),
                rewardsPolicy.rankPointsEnabled()
        );
        gameSessionRepository.save(session);
        initializeSessionQuestionPool(session, quiz);

        gamePlayerRepository.save(GamePlayer.create(session, viewerGuestSessionId, displayName, 1));
        return buildState("SOLO", "IN_GAME", session, viewerGuestSessionId);
    }

    private void finishSoloGame(GameSession session) {
        finishSoloGame(session, GameFinishReason.COMPLETED);
    }

    private void finishSoloGame(GameSession session, GameFinishReason finishReason) {
        Instant now = clock.instant();
        if (session.getStatus() == GameStatus.FINISHED) return;

        List<GamePlayer> players = gamePlayerRepository.findAllByGameSessionIdOrderByOrderIndexAsc(session.getId());

        if (session.getStage() == GameStage.QUESTION) {
            try {
                CurrentQuestion current = currentQuestion(session);
                ensureTimeoutAnswers(session, current.sessionQuestionId(), now);
            } catch (Exception ignored) {
            }
        }

        session.setStatus(GameStatus.FINISHED);
        session.setEndedAt(now);
        session.setFinishReason(finishReason == null ? GameFinishReason.COMPLETED : finishReason);
        gameSessionRepository.save(session);

        applyRewardsIfNeeded(session, players, now);
        updateThreeLivesRecordIfNeeded(session, players, now);
        refreshAchievementsForFinishedPlayers(players, now);
    }

    private void refreshAchievementsForFinishedPlayers(List<GamePlayer> players, Instant now) {
        if (players == null || players.isEmpty()) return;
        Set<Long> userIds = new LinkedHashSet<>();
        for (GamePlayer player : players) {
            if (player == null || player.getGuestSessionId() == null) continue;
            guestSessionRepository.findById(player.getGuestSessionId())
                    .map(GuestSession::getUserId)
                    .filter(Objects::nonNull)
                    .ifPresent(userIds::add);
        }
        for (Long userId : userIds) {
            userAchievementService.refreshForUser(userId, now);
        }
    }

    private GameSession requireSoloSession(String gameSessionId) {
        GameSession session = gameSessionRepository.findById(gameSessionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Game session not found"));
        if (!isSoloSession(session)) {
            throw new ResponseStatusException(CONFLICT, "This is not a solo game session");
        }
        return session;
    }

    private String createSoloSessionLobbyId() {
        return SOLO_SESSION_LOBBY_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    public Optional<GameSession> findActiveGameSessionForGuestSession(String guestSessionId) {
        if (guestSessionId == null || guestSessionId.isBlank()) return Optional.empty();

        Instant now = clock.instant();
        List<GameSession> activeSessions = gamePlayerRepository.findActiveSessionsByGuestSessionId(
                guestSessionId,
                GameStatus.IN_PROGRESS
        );
        for (GameSession session : activeSessions) {
            if (expireTrainingSessionIfIdle(session, now)) {
                continue;
            }
            return Optional.of(session);
        }
        return Optional.empty();
    }

    public Optional<ActiveGameDto> findCurrentActiveGame(HttpServletRequest request) {
        String guestSessionId = guestSessionService.findValidSession(request)
                .map(GuestSession::getId)
                .orElse(null);
        if (guestSessionId == null || guestSessionId.isBlank()) return Optional.empty();

        Optional<GameSession> active = findActiveGameSessionForGuestSession(guestSessionId);
        if (active.isEmpty()) return Optional.empty();

        GameSession session = active.get();
        if (isSoloSession(session)) {
            return Optional.of(new ActiveGameDto("SOLO", session.getId(), null));
        }

        return lobbyRepository.findById(session.getLobbyId())
                .map(lobby -> new ActiveGameDto("LOBBY", session.getId(), lobby.getCode()));
    }

    private Optional<Lobby> findActiveLobbyForGuestSession(String guestSessionId) {
        if (guestSessionId == null || guestSessionId.isBlank()) return Optional.empty();
        return participantRepository
                .findActiveParticipationsByGuestSessionId(guestSessionId, ACTIVE_LOBBY_STATUSES)
                .stream()
                .map(LobbyParticipant::getLobby)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private boolean isSoloSession(GameSession session) {
        return session != null && isSoloSessionLobbyId(session.getLobbyId());
    }

    private boolean isSoloSessionLobbyId(String lobbyId) {
        return lobbyId != null && lobbyId.startsWith(SOLO_SESSION_LOBBY_ID_PREFIX);
    }

    private String soloLobbyStatus(GameSession session) {
        return session != null && session.getStatus() == GameStatus.FINISHED ? "OPEN" : "IN_GAME";
    }
}
