package pl.mindrush.backend.lobby.events;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

@Component
public class WebSocketLobbyEventPublisher implements LobbyEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public WebSocketLobbyEventPublisher(SimpMessagingTemplate messagingTemplate, Clock clock) {
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    @Override
    public void lobbyUpdated(String lobbyCode) {
        Runnable publish = () -> messagingTemplate.convertAndSend(
                "/topic/lobbies/" + lobbyCode + "/lobby",
                new LobbyEventDto("LOBBY_UPDATED", lobbyCode, clock.instant().toString())
        );

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
}

