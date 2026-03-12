package pl.mindrush.backend.quiz;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.game.GameSessionRepository;
import pl.mindrush.backend.game.GameStatus;
import pl.mindrush.backend.lobby.Lobby;
import pl.mindrush.backend.lobby.LobbyParticipantRepository;
import pl.mindrush.backend.lobby.LobbyRepository;
import pl.mindrush.backend.lobby.LobbyStatus;
import pl.mindrush.backend.lobby.chat.LobbySystemMessageService;
import pl.mindrush.backend.lobby.events.LobbyEventPublisher;

import java.util.List;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
@Transactional
public class QuizUsageGuardService {

    private final LobbyRepository lobbyRepository;
    private final LobbyParticipantRepository lobbyParticipantRepository;
    private final LobbySystemMessageService lobbySystemMessageService;
    private final LobbyEventPublisher lobbyEventPublisher;
    private final GameSessionRepository gameSessionRepository;

    public QuizUsageGuardService(
            LobbyRepository lobbyRepository,
            LobbyParticipantRepository lobbyParticipantRepository,
            LobbySystemMessageService lobbySystemMessageService,
            LobbyEventPublisher lobbyEventPublisher,
            GameSessionRepository gameSessionRepository
    ) {
        this.lobbyRepository = lobbyRepository;
        this.lobbyParticipantRepository = lobbyParticipantRepository;
        this.lobbySystemMessageService = lobbySystemMessageService;
        this.lobbyEventPublisher = lobbyEventPublisher;
        this.gameSessionRepository = gameSessionRepository;
    }

    public void assertCanDeactivateOrDelete(Long quizId, String actionLabel) {
        if (quizId == null || quizId <= 0) return;

        long activeGameSessions = gameSessionRepository.countByQuizIdAndStatus(quizId, GameStatus.IN_PROGRESS);
        if (activeGameSessions > 0) {
            String action = normalizeActionLabel(actionLabel);
            throw new ResponseStatusException(
                    CONFLICT,
                    "Cannot %s right now. %s".formatted(action, activeGameUsageMessage(activeGameSessions))
            );
        }

        clearQuizFromOpenLobbies(quizId);
    }

    private static String normalizeActionLabel(String actionLabel) {
        String value = actionLabel == null ? "" : actionLabel.trim();
        return value.isBlank() ? "change this quiz" : value;
    }

    private void clearQuizFromOpenLobbies(Long quizId) {
        List<Lobby> lobbies = lobbyRepository.findAllBySelectedQuizIdAndStatus(quizId, LobbyStatus.OPEN);
        if (lobbies.isEmpty()) return;

        for (Lobby lobby : lobbies) {
            if (lobby == null) continue;
            lobby.setSelectedQuizId(null);
            lobbyParticipantRepository.clearReadyByLobbyId(lobby.getId());
        }
        lobbyRepository.saveAll(lobbies);

        for (Lobby lobby : lobbies) {
            if (lobby == null || lobby.getCode() == null) continue;
            lobbySystemMessageService.publish(
                    lobby.getCode(),
                    "The selected quiz is no longer available and was removed from this lobby. Please choose another quiz."
            );
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        }
    }

    private static String activeGameUsageMessage(long activeGameSessions) {
        return "%d active game session%s %s using this quiz. Wait until %s finish and try again.".formatted(
                activeGameSessions,
                activeGameSessions == 1 ? "" : "s",
                activeGameSessions == 1 ? "is" : "are",
                activeGameSessions == 1 ? "it" : "they"
        );
    }
}
