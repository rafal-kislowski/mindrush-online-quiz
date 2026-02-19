package pl.mindrush.backend.game.events;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.mindrush.backend.game.GameSession;
import pl.mindrush.backend.game.GameSessionRepository;
import pl.mindrush.backend.game.GameStatus;
import pl.mindrush.backend.lobby.Lobby;
import pl.mindrush.backend.lobby.LobbyRepository;
import pl.mindrush.backend.lobby.LobbyStatus;

import java.time.Clock;

@Component
public class WebSocketGameEventPublisher implements GameEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;
    private final LobbyRepository lobbyRepository;
    private final GameSessionRepository gameSessionRepository;

    public WebSocketGameEventPublisher(
            SimpMessagingTemplate messagingTemplate,
            Clock clock,
            LobbyRepository lobbyRepository,
            GameSessionRepository gameSessionRepository
    ) {
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
        this.lobbyRepository = lobbyRepository;
        this.gameSessionRepository = gameSessionRepository;
    }

    @Override
    public void gameUpdated(String lobbyCode) {
        Runnable publish = () -> {
            String serverTime = clock.instant().toString();
            EventState state = resolveEventState(lobbyCode);
            messagingTemplate.convertAndSend(
                    "/topic/lobbies/" + lobbyCode + "/game",
                    new GameEventDto("GAME_UPDATED", lobbyCode, serverTime, state.lobbyStatus(), state.stage())
            );
        };

        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
            return;
        }

        publish.run();
    }

    private EventState resolveEventState(String lobbyCode) {
        Lobby lobby = lobbyRepository.findByCode(lobbyCode).orElse(null);
        if (lobby == null) {
            return new EventState(null, null);
        }

        String lobbyStatus = lobby.getStatus().name();
        if (lobby.getStatus() != LobbyStatus.IN_GAME) {
            return new EventState(lobbyStatus, "NO_GAME");
        }

        GameSession session = gameSessionRepository
                .findFirstByLobbyIdAndStatusOrderByStartedAtDesc(lobby.getId(), GameStatus.IN_PROGRESS)
                .orElse(null);

        return new EventState(lobbyStatus, session == null ? "NO_GAME" : session.getStage().name());
    }

    private record EventState(String lobbyStatus, String stage) {
    }
}
