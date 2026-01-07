package pl.mindrush.backend.game;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.game.dto.GameOptionDto;
import pl.mindrush.backend.game.dto.GamePlayerDto;
import pl.mindrush.backend.game.dto.GameQuestionDto;
import pl.mindrush.backend.game.dto.GameStateDto;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionService;
import pl.mindrush.backend.lobby.*;
import pl.mindrush.backend.quiz.*;

import java.time.Instant;
import java.util.*;

import static org.springframework.http.HttpStatus.*;

@Service
@Transactional
public class GameService {

    private final GuestSessionService guestSessionService;
    private final LobbyRepository lobbyRepository;
    private final LobbyParticipantRepository participantRepository;
    private final QuizRepository quizRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAnswerOptionRepository optionRepository;
    private final GameSessionRepository gameSessionRepository;
    private final GameAnswerRepository gameAnswerRepository;

    public GameService(GuestSessionService guestSessionService, LobbyRepository lobbyRepository, LobbyParticipantRepository participantRepository, QuizRepository quizRepository, QuizQuestionRepository questionRepository, QuizAnswerOptionRepository optionRepository, GameSessionRepository gameSessionRepository, GameAnswerRepository gameAnswerRepository) {
        this.guestSessionService = guestSessionService;
        this.lobbyRepository = lobbyRepository;
        this.participantRepository = participantRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.gameSessionRepository = gameSessionRepository;
        this.gameAnswerRepository = gameAnswerRepository;
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

        long playerCount = participantRepository.countByLobbyId(lobby.getId());
        if (playerCount != lobby.getMaxPlayers()) {
            throw new ResponseStatusException(CONFLICT, "Game requires exactly " + lobby.getMaxPlayers() + " players");
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

        Instant now = Instant.now();
        GameSession session = GameSession.startNew(lobby.getId(), quiz.getId(), now);
        gameSessionRepository.save(session);

        lobby.setStatus(LobbyStatus.IN_GAME);
        lobbyRepository.save(lobby);

        return buildState(lobby, session, guestSession.getId());
    }

    @Transactional(readOnly = true)
    public GameStateDto getState(HttpServletRequest request, String lobbyCode) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);
        requireParticipant(lobby.getId(), guestSession.getId());

        Optional<GameSession> sessionOpt = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS);
        if (sessionOpt.isEmpty()) {
            return new GameStateDto(lobby.getCode(), lobby.getStatus().name(), "NONE", 0, 0, "NO_GAME", null, List.of());
        }

        return buildState(lobby, sessionOpt.get(), guestSession.getId());
    }

    public GameStateDto submitAnswer(HttpServletRequest request, String lobbyCode, Long questionId, Long optionId) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);
        requireParticipant(lobby.getId(), guestSession.getId());

        GameSession session = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS).orElseThrow(() -> new ResponseStatusException(CONFLICT, "No active game"));

        if (lobby.getStatus() != LobbyStatus.IN_GAME) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not in game");
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
        Instant now = Instant.now();
        try {
            gameAnswerRepository.save(GameAnswer.create(session, questionId, guestSession.getId(), optionId, correct, now));
        } catch (DataIntegrityViolationException ignored) {
        }

        return buildState(lobby, session, guestSession.getId());
    }

    public GameStateDto nextQuestion(HttpServletRequest request, String lobbyCode) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);

        if (!Objects.equals(lobby.getOwnerGuestSessionId(), guestSession.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can advance the game");
        }

        GameSession session = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS).orElseThrow(() -> new ResponseStatusException(CONFLICT, "No active game"));

        CurrentQuestion current = currentQuestion(session);
        long answered = gameAnswerRepository.countByGameSessionIdAndQuestionId(session.getId(), current.question().getId());
        long playerCount = participantRepository.countByLobbyId(lobby.getId());
        if (answered < playerCount) {
            throw new ResponseStatusException(CONFLICT, "Not all players have answered");
        }

        int nextIndex = session.getCurrentQuestionIndex() + 1;
        if (nextIndex >= current.totalQuestions()) {
            finishGame(lobby, session);
            return buildState(lobby, session, guestSession.getId());
        }

        session.setCurrentQuestionIndex(nextIndex);
        gameSessionRepository.save(session);

        return buildState(lobby, session, guestSession.getId());
    }

    public GameStateDto endGame(HttpServletRequest request, String lobbyCode) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = requireLobbyByCode(lobbyCode);

        if (!Objects.equals(lobby.getOwnerGuestSessionId(), guestSession.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can end the game");
        }

        GameSession session = gameSessionRepository.findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS).orElseThrow(() -> new ResponseStatusException(CONFLICT, "No active game"));

        finishGame(lobby, session);
        return buildState(lobby, session, guestSession.getId());
    }

    private void finishGame(Lobby lobby, GameSession session) {
        Instant now = Instant.now();
        session.setStatus(GameStatus.FINISHED);
        session.setEndedAt(now);
        gameSessionRepository.save(session);

        lobby.setStatus(LobbyStatus.OPEN);
        lobbyRepository.save(lobby);
    }

    private GameStateDto buildState(Lobby lobby, GameSession session, String viewerGuestSessionId) {
        List<LobbyParticipant> participants = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());

        long totalQuestions = questionRepository.countByQuizId(session.getQuizId());
        int questionIndex = session.getCurrentQuestionIndex();
        if (session.getStatus() == GameStatus.FINISHED || questionIndex >= totalQuestions) {
            List<GamePlayerDto> players = participants.stream().map(p -> new GamePlayerDto(p.getDisplayName(), false, null, gameAnswerRepository.countCorrectByGameSessionIdAndGuestSessionId(session.getId(), p.getGuestSessionId()))).toList();

            return new GameStateDto(lobby.getCode(), lobby.getStatus().name(), session.getStatus().name(), (int) Math.min(questionIndex + 1L, totalQuestions), (int) totalQuestions, "FINISHED", null, players);
        }

        CurrentQuestion current = currentQuestion(session);

        long answeredCount = gameAnswerRepository.countByGameSessionIdAndQuestionId(session.getId(), current.question().getId());
        String stage = answeredCount >= participants.size() ? "REVEAL" : "QUESTION";

        Map<String, GameAnswer> answersByGuestSessionId = gameAnswerRepository.findAllByGameSessionIdAndQuestionId(session.getId(), current.question().getId()).stream().collect(java.util.stream.Collectors.toMap(GameAnswer::getGuestSessionId, a -> a));

        List<GamePlayerDto> players = participants.stream().map(p -> {
            GameAnswer answer = answersByGuestSessionId.get(p.getGuestSessionId());
            boolean answered = answer != null;
            Boolean correct = "REVEAL".equals(stage) && answer != null ? answer.isCorrect() : null;
            long score = gameAnswerRepository.countCorrectByGameSessionIdAndGuestSessionId(session.getId(), p.getGuestSessionId());
            return new GamePlayerDto(p.getDisplayName(), answered, correct, score);
        }).toList();

        List<GameOptionDto> orderedOptions = shuffledOptions(session.getId(), viewerGuestSessionId, current.question().getId(), current.options()).stream().map(o -> new GameOptionDto(o.getId(), o.getText())).toList();

        GameQuestionDto questionDto = new GameQuestionDto(current.question().getId(), current.question().getPrompt(), orderedOptions);

        return new GameStateDto(lobby.getCode(), lobby.getStatus().name(), session.getStatus().name(), current.indexOneBased(), current.totalQuestions(), stage, questionDto, players);
    }

    private Lobby requireLobbyByCode(String code) {
        return lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));
    }

    private void requireParticipant(String lobbyId, String guestSessionId) {
        if (!participantRepository.existsByLobbyIdAndGuestSessionId(lobbyId, guestSessionId)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Not a lobby participant");
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

