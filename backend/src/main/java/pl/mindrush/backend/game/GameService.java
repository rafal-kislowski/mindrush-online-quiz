package pl.mindrush.backend.game;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.game.dto.GameOptionDto;
import pl.mindrush.backend.game.dto.GamePlayerDto;
import pl.mindrush.backend.game.dto.GameQuestionDto;
import pl.mindrush.backend.game.dto.GameStateDto;
import pl.mindrush.backend.game.events.GameEventPublisher;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionService;
import pl.mindrush.backend.lobby.*;
import pl.mindrush.backend.quiz.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.springframework.http.HttpStatus.*;

@Service
@Transactional
public class GameService {

    private final Clock clock;
    private final Duration guestQuestionDuration;
    private final Duration revealDuration;
    private final Duration preCountdownDuration;
    private final Duration finalRevealDuration;

    private final GuestSessionService guestSessionService;
    private final LobbyRepository lobbyRepository;
    private final LobbyParticipantRepository participantRepository;
    private final QuizRepository quizRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAnswerOptionRepository optionRepository;
    private final GameSessionRepository gameSessionRepository;
    private final GameAnswerRepository gameAnswerRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final GameEventPublisher gameEventPublisher;

    public GameService(
            Clock clock,
            @Value("${game.guest.question-duration:PT10S}") Duration guestQuestionDuration,
            @Value("${game.guest.reveal-duration:PT3S}") Duration revealDuration,
            @Value("${game.guest.pre-countdown-duration:PT4S}") Duration preCountdownDuration,
            @Value("${game.guest.final-reveal-duration:PT2S}") Duration finalRevealDuration,
            GuestSessionService guestSessionService,
            LobbyRepository lobbyRepository,
            LobbyParticipantRepository participantRepository,
            QuizRepository quizRepository,
            QuizQuestionRepository questionRepository,
            QuizAnswerOptionRepository optionRepository,
            GameSessionRepository gameSessionRepository,
            GameAnswerRepository gameAnswerRepository,
            GamePlayerRepository gamePlayerRepository,
            GameEventPublisher gameEventPublisher
    ) {
        this.clock = clock;
        this.guestQuestionDuration = guestQuestionDuration;
        this.revealDuration = revealDuration;
        this.preCountdownDuration = preCountdownDuration;
        this.finalRevealDuration = finalRevealDuration;
        this.guestSessionService = guestSessionService;
        this.lobbyRepository = lobbyRepository;
        this.participantRepository = participantRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.gameSessionRepository = gameSessionRepository;
        this.gameAnswerRepository = gameAnswerRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.gameEventPublisher = gameEventPublisher;
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

    public GameStateDto startGame(HttpServletRequest request, String lobbyCode, Long quizId) {
        if (quizId == null) {
            throw new ResponseStatusException(CONFLICT, "quizId is required");
        }

        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);

        if (!Objects.equals(lobby.getOwnerGuestSessionId(), guestSession.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can start the game");
        }

        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not open");
        }

        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));

        long questionCount = questionRepository.countByQuizId(quizId);
        if (questionCount == 0) {
            throw new ResponseStatusException(CONFLICT, "Quiz has no questions");
        }

        Optional<GameSession> existing = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS);
        if (existing.isPresent()) {
            throw new ResponseStatusException(CONFLICT, "Game is already in progress");
        }

        Instant now = clock.instant();
        GameSession session = GameSession.startNew(lobby.getId(), quiz.getId(), now, preCountdownDuration);
        gameSessionRepository.save(session);

        List<LobbyParticipant> lobbyPlayers = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
        for (int i = 0; i < lobbyPlayers.size(); i++) {
            LobbyParticipant p = lobbyPlayers.get(i);
            gamePlayerRepository.save(GamePlayer.create(session, p.getGuestSessionId(), p.getDisplayName(), i + 1));
        }

        lobby.setStatus(LobbyStatus.IN_GAME);
        lobbyRepository.save(lobby);

        gameEventPublisher.gameUpdated(lobby.getCode());
        return buildState(lobby, session, guestSession.getId());
    }

    public GameStateDto getState(HttpServletRequest request, String lobbyCode) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);
        requireParticipant(lobby.getId(), guestSession.getId());

        Optional<GameSession> sessionOpt = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS);
        if (sessionOpt.isEmpty()) {
            Optional<GameSession> last = gameSessionRepository.findFirstByLobbyIdOrderByStartedAtDesc(lobby.getId());
            if (last.isEmpty() || last.get().getStatus() != GameStatus.FINISHED) {
                return new GameStateDto(lobby.getCode(), lobby.getStatus().name(), "NONE", 0, 0, "NO_GAME", null, null, List.of(), null, null);
            }
            requireGamePlayer(last.get().getId(), guestSession.getId());
            return buildState(lobby, last.get(), guestSession.getId());
        }

        requireGamePlayer(sessionOpt.get().getId(), guestSession.getId());
        boolean advanced = advanceIfNeeded(lobby, sessionOpt.get());
        if (advanced) {
            gameEventPublisher.gameUpdated(lobby.getCode());
        }
        return buildState(lobby, sessionOpt.get(), guestSession.getId());
    }

    public GameStateDto submitAnswer(HttpServletRequest request, String lobbyCode, Long questionId, Long optionId) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);
        requireParticipant(lobby.getId(), guestSession.getId());

        GameSession session = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS).orElseThrow(() -> new ResponseStatusException(CONFLICT, "No active game"));
        requireGamePlayer(session.getId(), guestSession.getId());
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
        if (!Objects.equals(questionId, current.question().getId())) {
            throw new ResponseStatusException(CONFLICT, "Not the current question");
        }

        if (gameAnswerRepository.existsByGameSessionIdAndQuestionIdAndGuestSessionId(session.getId(), questionId, guestSession.getId())) {
            return buildState(lobby, session, guestSession.getId());
        }

        QuizAnswerOption selected = current.options().stream().filter(o -> Objects.equals(o.getId(), optionId)).findFirst().orElseThrow(() -> new ResponseStatusException(CONFLICT, "Invalid optionId"));

        boolean correct = selected.isCorrect();
        Instant now = clock.instant();
        try {
            gameAnswerRepository.save(GameAnswer.create(session, questionId, guestSession.getId(), optionId, correct, now));
        } catch (DataIntegrityViolationException ignored) {
        }

        advanceIfNeeded(lobby, session);
        gameEventPublisher.gameUpdated(lobby.getCode());
        return buildState(lobby, session, guestSession.getId());
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

        finishGame(lobby, session);
        gameEventPublisher.gameUpdated(lobby.getCode());
        return buildState(lobby, session, guestSession.getId());
    }

    private void finishGame(Lobby lobby, GameSession session) {
        Instant now = clock.instant();
        session.setStatus(GameStatus.FINISHED);
        session.setEndedAt(now);
        gameSessionRepository.save(session);

        lobby.setStatus(LobbyStatus.OPEN);
        lobbyRepository.save(lobby);
    }

    public void tickDueSessions() {
        Instant now = clock.instant();
        List<GameSession> due = gameSessionRepository.findAllByStatusAndStageEndsAtBefore(GameStatus.IN_PROGRESS, now);
        for (GameSession session : due) {
            Lobby lobby = lobbyRepository.findById(session.getLobbyId()).orElse(null);
            if (lobby == null) continue;
            boolean changed = advanceIfNeeded(lobby, session);
            if (changed) {
                gameEventPublisher.gameUpdated(lobby.getCode());
            }
        }
    }

    private GameStateDto buildState(Lobby lobby, GameSession session, String viewerGuestSessionId) {
        List<GamePlayer> players = gamePlayerRepository.findAllByGameSessionIdOrderByOrderIndexAsc(session.getId());

        long totalQuestions = questionRepository.countByQuizId(session.getQuizId());
        int questionIndex = session.getCurrentQuestionIndex();
        if (session.getStatus() == GameStatus.FINISHED || questionIndex >= totalQuestions) {
            List<GamePlayerDto> playersDto = players.stream()
                    .map(p -> new GamePlayerDto(
                            p.getDisplayName(),
                            false,
                            null,
                            gameAnswerRepository.countCorrectByGameSessionIdAndGuestSessionId(session.getId(), p.getGuestSessionId())
                    ))
                    .toList();

            return new GameStateDto(
                    lobby.getCode(),
                    lobby.getStatus().name(),
                    session.getStatus().name(),
                    (int) Math.min(questionIndex + 1L, totalQuestions),
                    (int) totalQuestions,
                    "FINISHED",
                    null,
                    null,
                    playersDto,
                    session.getId(),
                    null
            );
        }

        if (session.getStage() == GameStage.PRE_COUNTDOWN) {
            List<GamePlayerDto> playersDto = players.stream()
                    .map(p -> new GamePlayerDto(
                            p.getDisplayName(),
                            false,
                            null,
                            gameAnswerRepository.countCorrectByGameSessionIdAndGuestSessionId(session.getId(), p.getGuestSessionId())
                    ))
                    .toList();

            return new GameStateDto(
                    lobby.getCode(),
                    lobby.getStatus().name(),
                    session.getStatus().name(),
                    1,
                    (int) totalQuestions,
                    session.getStage().name(),
                    session.getStageEndsAt().toString(),
                    null,
                    playersDto,
                    session.getId(),
                    null
            );
        }

        CurrentQuestion current = currentQuestion(session);

        boolean reveal = session.getStage() == GameStage.REVEAL;

        Map<String, GameAnswer> answersByGuestSessionId = gameAnswerRepository.findAllByGameSessionIdAndQuestionId(session.getId(), current.question().getId()).stream().collect(java.util.stream.Collectors.toMap(GameAnswer::getGuestSessionId, a -> a));

        List<GamePlayerDto> playersDto = players.stream().map(p -> {
            GameAnswer answer = answersByGuestSessionId.get(p.getGuestSessionId());
            boolean answered = answer != null && answer.getSelectedOptionId() != null;
            Boolean correct = reveal && answer != null ? answer.isCorrect() : null;
            long score = gameAnswerRepository.countCorrectByGameSessionIdAndGuestSessionId(session.getId(), p.getGuestSessionId());
            return new GamePlayerDto(p.getDisplayName(), answered, correct, score);
        }).toList();

        List<GameOptionDto> orderedOptions = shuffledOptions(session.getId(), viewerGuestSessionId, current.question().getId(), current.options()).stream()
                .map(o -> new GameOptionDto(o.getId(), o.getText(), o.getImageUrl()))
                .toList();

        GameQuestionDto questionDto = new GameQuestionDto(current.question().getId(), current.question().getPrompt(), current.question().getImageUrl(), orderedOptions);

        Long correctOptionId = null;
        if (reveal) {
            correctOptionId = current.options().stream()
                    .filter(QuizAnswerOption::isCorrect)
                    .map(QuizAnswerOption::getId)
                    .findFirst()
                    .orElse(null);
        }

        return new GameStateDto(
                lobby.getCode(),
                lobby.getStatus().name(),
                session.getStatus().name(),
                current.indexOneBased(),
                current.totalQuestions(),
                session.getStage().name(),
                session.getStageEndsAt().toString(),
                questionDto,
                playersDto,
                session.getId(),
                correctOptionId
        );
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

    private boolean advanceIfNeeded(Lobby lobby, GameSession session) {
        if (session.getStatus() != GameStatus.IN_PROGRESS) return false;

        Instant now = clock.instant();

        if (session.getStage() == GameStage.PRE_COUNTDOWN) {
            if (now.isBefore(session.getStageEndsAt())) return false;
            session.setStage(GameStage.QUESTION);
            session.setStageEndsAt(now.plus(guestQuestionDuration));
            gameSessionRepository.save(session);
            return true;
        }

        if (session.getStage() == GameStage.QUESTION) {
            CurrentQuestion current = currentQuestion(session);
            long playerCount = gamePlayerRepository.countByGameSessionId(session.getId());
            long answered = gameAnswerRepository.countByGameSessionIdAndQuestionId(session.getId(), current.question().getId());

            boolean timeUp = !now.isBefore(session.getStageEndsAt());
            boolean allAnswered = answered >= playerCount;

            if (timeUp) {
                ensureTimeoutAnswers(session, current.question().getId(), now);
                allAnswered = true;
            }

            if (allAnswered) {
                session.setStage(GameStage.REVEAL);
                boolean isLastQuestion = session.getCurrentQuestionIndex() + 1 >= current.totalQuestions();
                session.setStageEndsAt(now.plus(isLastQuestion ? finalRevealDuration : revealDuration));
                gameSessionRepository.save(session);
                return true;
            }
            return false;
        }

        if (session.getStage() == GameStage.REVEAL) {
            if (now.isBefore(session.getStageEndsAt())) return false;

            int nextIndex = session.getCurrentQuestionIndex() + 1;
            long totalQuestions = questionRepository.countByQuizId(session.getQuizId());
            if (nextIndex >= totalQuestions) {
                finishGame(lobby, session);
                return true;
            }

            session.setCurrentQuestionIndex(nextIndex);
            session.setStage(GameStage.QUESTION);
            session.setStageEndsAt(now.plus(guestQuestionDuration));
            gameSessionRepository.save(session);
            return true;
        }

        return false;
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
                gameAnswerRepository.save(GameAnswer.create(session, questionId, p.getGuestSessionId(), null, false, now));
            } catch (DataIntegrityViolationException ignored) {
            }
        }
    }

    private CurrentQuestion currentQuestion(GameSession session) {
        List<QuizQuestion> questions = questionRepository.findAllByQuizIdOrderByOrderIndexAsc(session.getQuizId());
        int total = questions.size();
        int idx = session.getCurrentQuestionIndex();
        if (idx < 0 || idx >= total) {
            throw new ResponseStatusException(CONFLICT, "Invalid question index");
        }

        QuizQuestion question = questions.get(idx);
        List<QuizAnswerOption> options = optionRepository.findAllByQuestionIdOrderByOrderIndexAsc(question.getId());
        return new CurrentQuestion(question, options, idx + 1, total);
    }

    private record CurrentQuestion(QuizQuestion question, List<QuizAnswerOption> options, int indexOneBased,
                                   int totalQuestions) {
    }
}
