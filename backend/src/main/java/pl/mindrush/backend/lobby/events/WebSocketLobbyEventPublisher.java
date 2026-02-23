package pl.mindrush.backend.lobby.events;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.mindrush.backend.lobby.Lobby;
import pl.mindrush.backend.lobby.LobbyParticipant;
import pl.mindrush.backend.lobby.LobbyParticipantRepository;
import pl.mindrush.backend.lobby.LobbyRepository;
import pl.mindrush.backend.lobby.LobbySummaryMapper;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class WebSocketLobbyEventPublisher implements LobbyEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;
    private final LobbyRepository lobbyRepository;
    private final LobbyParticipantRepository participantRepository;
    private final LobbySummaryMapper lobbySummaryMapper;

    public WebSocketLobbyEventPublisher(
            SimpMessagingTemplate messagingTemplate,
            Clock clock,
            LobbyRepository lobbyRepository,
            LobbyParticipantRepository participantRepository,
            LobbySummaryMapper lobbySummaryMapper
    ) {
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
        this.lobbyRepository = lobbyRepository;
        this.participantRepository = participantRepository;
        this.lobbySummaryMapper = lobbySummaryMapper;
    }

    @Override
    public void lobbyUpdated(String lobbyCode) {
        runAfterCommit(() -> {
            String serverTime = clock.instant().toString();
            messagingTemplate.convertAndSend(
                    "/topic/lobbies/" + lobbyCode + "/lobby",
                    new LobbyEventDto("LOBBY_UPDATED", lobbyCode, serverTime, null)
            );
            publishSnapshotsToLobbyParticipants(lobbyCode, serverTime);
        });
    }

    @Override
    public void participantKicked(String lobbyCode, String guestSessionId) {
        if (guestSessionId == null || guestSessionId.isBlank()) return;
        runAfterCommit(() -> messagingTemplate.convertAndSendToUser(
                guestSessionId,
                "/queue/lobbies/" + lobbyCode + "/lobby",
                new LobbyEventDto("LOBBY_KICKED", lobbyCode, clock.instant().toString(), null)
        ));
    }

    @Override
    public void participantBanned(String lobbyCode, String guestSessionId) {
        if (guestSessionId == null || guestSessionId.isBlank()) return;
        runAfterCommit(() -> messagingTemplate.convertAndSendToUser(
                guestSessionId,
                "/queue/lobbies/" + lobbyCode + "/lobby",
                new LobbyEventDto("LOBBY_BANNED", lobbyCode, clock.instant().toString(), null)
        ));
    }

    private void publishSnapshotsToLobbyParticipants(String lobbyCode, String serverTime) {
        Lobby lobby = lobbyRepository.findByCode(lobbyCode).orElse(null);
        if (lobby == null) return;

        List<LobbyParticipant> participants = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
        Set<String> recipients = new LinkedHashSet<>();
        recipients.add(lobby.getOwnerGuestSessionId());
        for (LobbyParticipant participant : participants) {
            recipients.add(participant.getGuestSessionId());
        }

        for (String guestSessionId : recipients) {
            if (guestSessionId == null || guestSessionId.isBlank()) continue;
            messagingTemplate.convertAndSendToUser(
                    guestSessionId,
                    "/queue/lobbies/" + lobbyCode + "/lobby",
                    new LobbyEventDto(
                            "LOBBY_SNAPSHOT",
                            lobbyCode,
                            serverTime,
                            lobbySummaryMapper.summary(lobby, guestSessionId)
                    )
            );
        }
    }

    private void runAfterCommit(Runnable publish) {
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
