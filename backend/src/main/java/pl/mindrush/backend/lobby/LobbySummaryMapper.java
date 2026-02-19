package pl.mindrush.backend.lobby;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LobbySummaryMapper {

    private final LobbyParticipantRepository participantRepository;

    public LobbySummaryMapper(LobbyParticipantRepository participantRepository) {
        this.participantRepository = participantRepository;
    }

    public Map<String, Object> summary(Lobby lobby, String viewerGuestSessionId) {
        List<LobbyParticipant> participants = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
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
        res.put("players", participants.stream().map(p -> Map.of(
                "displayName", p.getDisplayName(),
                "joinedAt", p.getJoinedAt().toString()
        )).toList());
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
}
