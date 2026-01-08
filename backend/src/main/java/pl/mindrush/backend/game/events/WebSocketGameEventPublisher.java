package pl.mindrush.backend.game.events;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

@Component
public class WebSocketGameEventPublisher implements GameEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public WebSocketGameEventPublisher(SimpMessagingTemplate messagingTemplate, Clock clock) {
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    @Override
    public void gameUpdated(String lobbyCode) {
        Runnable publish = () -> messagingTemplate.convertAndSend(
                "/topic/lobbies/" + lobbyCode + "/game",
                new GameEventDto("GAME_UPDATED", lobbyCode, clock.instant().toString())
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

