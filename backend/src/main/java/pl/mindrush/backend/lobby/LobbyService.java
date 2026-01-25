package pl.mindrush.backend.lobby;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionService;
import pl.mindrush.backend.lobby.events.LobbyEventPublisher;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class LobbyService {

    public static final int GUEST_LOBBY_MAX_PLAYERS = 2;
    public static final int AUTH_LOBBY_MIN_PLAYERS = 2;
    public static final int AUTH_LOBBY_MAX_PLAYERS = 5;

    private static final char[] CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int CODE_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final GuestSessionService guestSessionService;
    private final LobbyRepository lobbyRepository;
    private final LobbyParticipantRepository participantRepository;
    private final LobbyEventPublisher lobbyEventPublisher;

    public LobbyService(
            GuestSessionService guestSessionService,
            LobbyRepository lobbyRepository,
            LobbyParticipantRepository participantRepository,
            LobbyEventPublisher lobbyEventPublisher
    ) {
        this.guestSessionService = guestSessionService;
        this.lobbyRepository = lobbyRepository;
        this.participantRepository = participantRepository;
        this.lobbyEventPublisher = lobbyEventPublisher;
    }

    public Map<String, Object> createLobby(
            HttpServletRequest request,
            String rawPassword,
            Integer requestedMaxPlayers,
            boolean authenticatedUser
    ) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Instant now = Instant.now();

        String code = generateUniqueCode();
        String passwordHash = (rawPassword == null || rawPassword.isBlank()) ? null : passwordEncoder.encode(rawPassword);

        int maxPlayers = resolveMaxPlayers(requestedMaxPlayers, authenticatedUser);
        Lobby lobby = Lobby.createNew(code, guestSession.getId(), maxPlayers, passwordHash, now);
        lobbyRepository.save(lobby);

        String displayName = uniqueDisplayNameForLobby(lobby.getId(), guestSession.getDisplayName());
        participantRepository.save(LobbyParticipant.createGuest(lobby, guestSession.getId(), displayName, now));

        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        return lobbySummary(lobby, guestSession.getId());
    }

    public Map<String, Object> setLobbyMaxPlayers(
            HttpServletRequest request,
            String code,
            int maxPlayers,
            boolean authenticatedUser
    ) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (!guestSession.getId().equals(lobby.getOwnerGuestSessionId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can change lobby settings");
        }
        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not open");
        }

        if (!authenticatedUser && maxPlayers > GUEST_LOBBY_MAX_PLAYERS) {
            throw new ResponseStatusException(FORBIDDEN, "Login required for lobbies larger than 2 players");
        }
        if (maxPlayers < AUTH_LOBBY_MIN_PLAYERS || maxPlayers > AUTH_LOBBY_MAX_PLAYERS) {
            throw new ResponseStatusException(BAD_REQUEST, "maxPlayers must be between 2 and 5");
        }

        long current = participantRepository.countByLobbyId(lobby.getId());
        if (maxPlayers < current) {
            throw new ResponseStatusException(CONFLICT, "maxPlayers cannot be lower than current player count");
        }

        if (lobby.getMaxPlayers() != maxPlayers) {
            lobby.setMaxPlayers(maxPlayers);
            lobbyRepository.save(lobby);
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        }

        return lobbySummary(lobby, guestSession.getId());
    }

    public Map<String, Object> getLobby(HttpServletRequest request, String code) {
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));
        String viewerId = guestSessionService.findValidSession(request).map(GuestSession::getId).orElse(null);
        return lobbySummary(lobby, viewerId);
    }

    public Map<String, Object> joinLobby(HttpServletRequest request, String code, String rawPassword) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is closed");
        }

        if (participantRepository.existsByLobbyIdAndGuestSessionId(lobby.getId(), guestSession.getId())) {
            return lobbySummary(lobby, guestSession.getId());
        }

        if (lobby.hasPassword()) {
            if (rawPassword == null || rawPassword.isBlank() || !passwordEncoder.matches(rawPassword, lobby.getPasswordHash())) {
                throw new ResponseStatusException(FORBIDDEN, "Invalid lobby password");
            }
        }

        long count = participantRepository.countByLobbyId(lobby.getId());
        if (count >= lobby.getMaxPlayers()) {
            throw new ResponseStatusException(CONFLICT, "Lobby is full");
        }

        Instant now = Instant.now();
        if (count == 0 && lobby.getEmptySince() != null) {
            lobby.setEmptySince(null);
            if (!guestSession.getId().equals(lobby.getOwnerGuestSessionId())) {
                lobby.setOwnerGuestSessionId(guestSession.getId());
            }
            lobbyRepository.save(lobby);
        }
        String displayName = uniqueDisplayNameForLobby(lobby.getId(), guestSession.getDisplayName());
        participantRepository.save(LobbyParticipant.createGuest(lobby, guestSession.getId(), displayName, now));

        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        return lobbySummary(lobby, guestSession.getId());
    }

    public Map<String, Object> setLobbyPassword(HttpServletRequest request, String code, String rawPassword) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (!guestSession.getId().equals(lobby.getOwnerGuestSessionId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can change privacy settings");
        }
        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new ResponseStatusException(CONFLICT, "Lobby is not open");
        }

        String passwordHash = (rawPassword == null || rawPassword.isBlank()) ? null : passwordEncoder.encode(rawPassword);
        lobby.setPasswordHash(passwordHash);
        lobbyRepository.save(lobby);

        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        return lobbySummary(lobby, guestSession.getId());
    }

    public LeaveResult leaveLobby(HttpServletRequest request, String code) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (lobby.getStatus() == LobbyStatus.IN_GAME) {
            throw new ResponseStatusException(CONFLICT, "Cannot leave while game is in progress");
        }

        participantRepository.deleteByLobbyIdAndGuestSessionId(lobby.getId(), guestSession.getId());

        long remaining = participantRepository.countByLobbyId(lobby.getId());
        if (remaining == 0) {
            lobby.setEmptySince(Instant.now());
            lobbyRepository.save(lobby);
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
            return LeaveResult.deleted();
        }

        if (guestSession.getId().equals(lobby.getOwnerGuestSessionId())) {
            LobbyParticipant newOwner = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId()).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Cannot transfer lobby ownership"));

            lobby.setOwnerGuestSessionId(newOwner.getGuestSessionId());
            lobbyRepository.save(lobby);
        }

        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        return LeaveResult.updated(lobbySummary(lobby, guestSession.getId()));
    }

    public Map<String, Object> closeLobby(HttpServletRequest request, String code) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (!guestSession.getId().equals(lobby.getOwnerGuestSessionId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the lobby owner can close the lobby");
        }

        if (lobby.getStatus() == LobbyStatus.IN_GAME) {
            throw new ResponseStatusException(CONFLICT, "Game is in progress");
        }

        if (lobby.getStatus() != LobbyStatus.CLOSED) {
            lobby.setStatus(LobbyStatus.CLOSED);
            lobbyRepository.save(lobby);
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
        }

        return lobbySummary(lobby, guestSession.getId());
    }

    public void handleGuestDisconnected(String guestSessionId, String code) {
        if (guestSessionId == null || guestSessionId.isBlank()) return;

        Lobby lobby = lobbyRepository.findByCode(code).orElse(null);
        if (lobby == null) return;
        if (lobby.getStatus() != LobbyStatus.OPEN) return;

        long deleted = participantRepository.deleteByLobbyIdAndGuestSessionId(lobby.getId(), guestSessionId);
        if (deleted == 0) return;

        long remaining = participantRepository.countByLobbyId(lobby.getId());
        if (remaining == 0) {
            lobby.setEmptySince(Instant.now());
            lobbyRepository.save(lobby);
            lobbyEventPublisher.lobbyUpdated(lobby.getCode());
            return;
        }

        if (guestSessionId.equals(lobby.getOwnerGuestSessionId())) {
            LobbyParticipant newOwner = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId()).stream()
                    .findFirst()
                    .orElse(null);
            if (newOwner != null) {
                lobby.setOwnerGuestSessionId(newOwner.getGuestSessionId());
                lobbyRepository.save(lobby);
            }
        }

        lobbyEventPublisher.lobbyUpdated(lobby.getCode());
    }

    private Map<String, Object> lobbySummary(Lobby lobby, String viewerGuestSessionId) {
        List<LobbyParticipant> participants = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
        boolean isOwner = viewerGuestSessionId != null && viewerGuestSessionId.equals(lobby.getOwnerGuestSessionId());
        boolean isParticipant = viewerGuestSessionId != null && participantRepository.existsByLobbyIdAndGuestSessionId(lobby.getId(), viewerGuestSessionId);

        if (lobby.hasPassword() && !isParticipant && !isOwner) {
            return Map.of(
                    "code", lobby.getCode(),
                    "hasPassword", true,
                    "isOwner", false,
                    "isParticipant", false
            );
        }

        return Map.of(
                "code", lobby.getCode(),
                "status", lobby.getStatus().name(),
                "maxPlayers", lobby.getMaxPlayers(),
                "players", participants.stream().map(p -> Map.of(
                        "displayName", p.getDisplayName(),
                        "joinedAt", p.getJoinedAt().toString()
                )).toList(),
                "hasPassword", lobby.hasPassword(),
                "createdAt", lobby.getCreatedAt().toString(),
                "isOwner", isOwner,
                "isParticipant", isParticipant
        );
    }

    private String generateUniqueCode() {
        for (int i = 0; i < 20; i++) {
            String code = randomCode();
            if (!lobbyRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new ResponseStatusException(CONFLICT, "Failed to generate unique lobby code");
    }

    private static int resolveMaxPlayers(Integer requestedMaxPlayers, boolean authenticatedUser) {
        if (requestedMaxPlayers == null) return GUEST_LOBBY_MAX_PLAYERS;

        if (!authenticatedUser) {
            if (requestedMaxPlayers == GUEST_LOBBY_MAX_PLAYERS) return GUEST_LOBBY_MAX_PLAYERS;
            if (requestedMaxPlayers > GUEST_LOBBY_MAX_PLAYERS) {
                throw new ResponseStatusException(FORBIDDEN, "Login required for lobbies larger than 2 players");
            }
            throw new ResponseStatusException(BAD_REQUEST, "maxPlayers must be at least 2");
        }

        if (requestedMaxPlayers < AUTH_LOBBY_MIN_PLAYERS || requestedMaxPlayers > AUTH_LOBBY_MAX_PLAYERS) {
            throw new ResponseStatusException(BAD_REQUEST, "maxPlayers must be between 2 and 5");
        }
        return requestedMaxPlayers;
    }

    private String randomCode() {
        char[] buf = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            buf[i] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        }
        return new String(buf);
    }

    private static String normalizeDisplayName(String displayName, String guestSessionId) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        String suffix = guestSessionId.length() >= 4 ? guestSessionId.substring(0, 4) : guestSessionId;
        return "Guest_" + suffix;
    }

    private String uniqueDisplayNameForLobby(String lobbyId, String preferredDisplayName) {
        String base = preferredDisplayName == null ? "" : preferredDisplayName.trim();
        if (base.isBlank()) {
            base = "Guest";
        }

        List<String> existing = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobbyId)
                .stream()
                .map(LobbyParticipant::getDisplayName)
                .toList();

        if (!existing.contains(base)) return base;
        for (int i = 2; i <= 99; i++) {
            String candidate = base + "-" + i;
            if (!existing.contains(candidate)) return candidate;
        }
        throw new ResponseStatusException(CONFLICT, "Nickname conflict");
    }

    public sealed interface LeaveResult permits LeaveResult.Deleted, LeaveResult.Updated {
        static LeaveResult deleted() {
            return new Deleted();
        }

        static LeaveResult updated(Map<String, Object> lobby) {
            return new Updated(lobby);
        }

        final class Deleted implements LeaveResult {
        }

        record Updated(Map<String, Object> lobby) implements LeaveResult {
        }
    }
}
