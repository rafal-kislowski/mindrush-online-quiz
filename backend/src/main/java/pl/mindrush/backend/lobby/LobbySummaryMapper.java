package pl.mindrush.backend.lobby;

import org.springframework.stereotype.Component;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionRepository;
import pl.mindrush.backend.lobby.presence.LobbyRealtimePresenceService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class LobbySummaryMapper {

    private final LobbyParticipantRepository participantRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final AppUserRepository appUserRepository;
    private final LobbyRealtimePresenceService realtimePresenceService;

    public LobbySummaryMapper(
            LobbyParticipantRepository participantRepository,
            GuestSessionRepository guestSessionRepository,
            AppUserRepository appUserRepository,
            LobbyRealtimePresenceService realtimePresenceService
    ) {
        this.participantRepository = participantRepository;
        this.guestSessionRepository = guestSessionRepository;
        this.appUserRepository = appUserRepository;
        this.realtimePresenceService = realtimePresenceService;
    }

    public Map<String, Object> summary(Lobby lobby, String viewerGuestSessionId) {
        List<LobbyParticipant> participants = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
        Map<String, GuestSession> sessionsByGuestSessionId = resolveGuestSessionsById(participants);
        Map<String, Boolean> authenticatedByGuestSessionId = resolveAuthenticatedByGuestSessionId(sessionsByGuestSessionId);
        Map<String, Integer> rankPointsByGuestSessionId = resolveRankPointsByGuestSessionId(sessionsByGuestSessionId);
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
            locked.put("rankingEnabled", lobby.isRankingEnabled());
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
                    "rankPoints", rankPointsByGuestSessionId.getOrDefault(p.getGuestSessionId(), 0),
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
        res.put("rankingEnabled", lobby.isRankingEnabled());
        return res;
    }

    private Map<String, GuestSession> resolveGuestSessionsById(List<LobbyParticipant> participants) {
        if (participants == null || participants.isEmpty()) return Map.of();

        List<String> ids = participants.stream()
                .map(LobbyParticipant::getGuestSessionId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();

        Map<String, GuestSession> out = new HashMap<>();
        for (GuestSession session : guestSessionRepository.findAllById(ids)) {
            if (session == null || session.getId() == null) continue;
            out.put(session.getId(), session);
        }
        return out;
    }

    private static Map<String, Boolean> resolveAuthenticatedByGuestSessionId(
            Map<String, GuestSession> sessionsByGuestSessionId
    ) {
        if (sessionsByGuestSessionId == null || sessionsByGuestSessionId.isEmpty()) return Map.of();
        Map<String, Boolean> out = new HashMap<>();
        for (Map.Entry<String, GuestSession> entry : sessionsByGuestSessionId.entrySet()) {
            String guestSessionId = entry.getKey();
            GuestSession session = entry.getValue();
            if (guestSessionId == null || guestSessionId.isBlank() || session == null) continue;
            out.put(guestSessionId, session.getUserId() != null);
        }
        return out;
    }

    private Map<String, Integer> resolveRankPointsByGuestSessionId(
            Map<String, GuestSession> sessionsByGuestSessionId
    ) {
        if (sessionsByGuestSessionId == null || sessionsByGuestSessionId.isEmpty()) return Map.of();

        List<Long> userIds = sessionsByGuestSessionId.values().stream()
                .map(GuestSession::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Integer> rankPointsByUserId = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (AppUser user : appUserRepository.findAllById(userIds)) {
                if (user == null || user.getId() == null) continue;
                rankPointsByUserId.put(user.getId(), user.getRankPoints());
            }
        }

        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, GuestSession> entry : sessionsByGuestSessionId.entrySet()) {
            String guestSessionId = entry.getKey();
            GuestSession session = entry.getValue();
            if (guestSessionId == null || guestSessionId.isBlank() || session == null) continue;
            Long userId = session.getUserId();
            int rankPoints = userId == null
                    ? session.getRankPoints()
                    : rankPointsByUserId.getOrDefault(userId, session.getRankPoints());
            out.put(guestSessionId, Math.max(0, rankPoints));
        }
        return out;
    }
}
