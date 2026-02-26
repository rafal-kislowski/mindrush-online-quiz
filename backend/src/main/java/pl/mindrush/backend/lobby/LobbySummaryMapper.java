package pl.mindrush.backend.lobby;

import org.springframework.stereotype.Component;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionRepository;
import pl.mindrush.backend.lobby.presence.LobbyRealtimePresenceService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LobbySummaryMapper {

    private final LobbyParticipantRepository participantRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final LobbyRealtimePresenceService realtimePresenceService;

    public LobbySummaryMapper(
            LobbyParticipantRepository participantRepository,
            GuestSessionRepository guestSessionRepository,
            LobbyRealtimePresenceService realtimePresenceService
    ) {
        this.participantRepository = participantRepository;
        this.guestSessionRepository = guestSessionRepository;
        this.realtimePresenceService = realtimePresenceService;
    }

    public Map<String, Object> summary(Lobby lobby, String viewerGuestSessionId) {
        List<LobbyParticipant> participants = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
        Map<String, Boolean> authenticatedByGuestSessionId = resolveAuthenticatedByGuestSessionId(participants);
        boolean isOwner = viewerGuestSessionId != null && viewerGuestSessionId.equals(lobby.getOwnerGuestSessionId());
        boolean isParticipant = viewerGuestSessionId != null
                && participants.stream().anyMatch(p -> viewerGuestSessionId.equals(p.getGuestSessionId()));

        if (lobby.hasPassword() && !isParticipant && !isOwner) {
            Map<String, Object> locked = new HashMap<>();
            locked.put("code", lobby.getCode());
            locked.put("hasPassword", true);
            locked.put("isOwner", false);
            locked.put("isParticipant", false);
            locked.put("selectedQuizId", lobby.getSelectedQuizId());
            return locked;
        }

        Map<String, Object> res = new HashMap<>();
        res.put("code", lobby.getCode());
        res.put("status", lobby.getStatus().name());
        res.put("maxPlayers", lobby.getMaxPlayers());
        res.put("players", participants.stream().map(p -> {
            boolean isYou = viewerGuestSessionId != null && viewerGuestSessionId.equals(p.getGuestSessionId());
            boolean away = !isYou && !realtimePresenceService.isLobbyViewActive(p.getGuestSessionId(), lobby.getCode());
            boolean isAuthenticated = authenticatedByGuestSessionId.getOrDefault(p.getGuestSessionId(), false);
            return Map.of(
                    "participantId", p.getId(),
                    "displayName", p.getDisplayName(),
                    "isAuthenticated", isAuthenticated,
                    "joinedAt", p.getJoinedAt().toString(),
                    "ready", p.isReady(),
                    "away", away,
                    "isOwner", p.getGuestSessionId().equals(lobby.getOwnerGuestSessionId()),
                    "isYou", isYou
            );
        }).toList());
        res.put("hasPassword", lobby.hasPassword());
        if (isOwner) {
            res.put("pin", lobby.getPinCode());
        }
        res.put("createdAt", lobby.getCreatedAt().toString());
        res.put("isOwner", isOwner);
        res.put("isParticipant", isParticipant);
        res.put("selectedQuizId", lobby.getSelectedQuizId());
        return res;
    }

    private Map<String, Boolean> resolveAuthenticatedByGuestSessionId(List<LobbyParticipant> participants) {
        if (participants == null || participants.isEmpty()) return Map.of();

        List<String> ids = participants.stream()
                .map(LobbyParticipant::getGuestSessionId)
                .filter(id -> id != null && !id.isBlank())
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
}
