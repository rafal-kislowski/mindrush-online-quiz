package pl.mindrush.backend.lobby;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.mindrush.backend.guest.GuestSessionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class LobbyPresenceCleanupScheduler {

    private final GuestSessionRepository guestSessionRepository;
    private final LobbyParticipantRepository participantRepository;
    private final LobbyService lobbyService;
    private final Duration timeout;

    public LobbyPresenceCleanupScheduler(
            GuestSessionRepository guestSessionRepository,
            LobbyParticipantRepository participantRepository,
            LobbyService lobbyService,
            @Value("${lobby.presence.timeout:PT25S}") Duration timeout
    ) {
        this.guestSessionRepository = guestSessionRepository;
        this.participantRepository = participantRepository;
        this.lobbyService = lobbyService;
        this.timeout = timeout;
    }

    @Scheduled(fixedDelayString = "${lobby.presence.cleanup.fixedDelayMs:5000}")
    @Transactional
    public void cleanupStaleParticipants() {
        Instant cutoff = Instant.now().minus(timeout);
        List<String> staleSessionIds = guestSessionRepository.findIdsLastSeenBefore(cutoff);
        if (staleSessionIds.isEmpty()) return;

        List<LobbyParticipant> staleParticipants = participantRepository.findAllByGuestSessionIdIn(staleSessionIds);
        if (staleParticipants.isEmpty()) return;

        for (LobbyParticipant p : staleParticipants) {
            lobbyService.handleGuestDisconnected(p.getGuestSessionId(), p.getLobby().getCode());
        }
    }
}

