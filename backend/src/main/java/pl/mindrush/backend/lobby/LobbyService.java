package pl.mindrush.backend.lobby;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionService;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class LobbyService {

    public static final int GUEST_LOBBY_MAX_PLAYERS = 2;

    private static final char[] CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int CODE_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final GuestSessionService guestSessionService;
    private final LobbyRepository lobbyRepository;
    private final LobbyParticipantRepository participantRepository;

    public LobbyService(
            GuestSessionService guestSessionService,
            LobbyRepository lobbyRepository,
            LobbyParticipantRepository participantRepository
    ) {
        this.guestSessionService = guestSessionService;
        this.lobbyRepository = lobbyRepository;
        this.participantRepository = participantRepository;
    }

    public Map<String, Object> createLobby(HttpServletRequest request, String rawPassword) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Instant now = Instant.now();

        String code = generateUniqueCode();
        String passwordHash = (rawPassword == null || rawPassword.isBlank()) ? null : passwordEncoder.encode(rawPassword);

        Lobby lobby = Lobby.createNew(code, guestSession.getId(), GUEST_LOBBY_MAX_PLAYERS, passwordHash, now);
        lobbyRepository.save(lobby);

        String displayName = uniqueDisplayNameForLobby(lobby.getId(), guestSession.getDisplayName());
        participantRepository.save(LobbyParticipant.createGuest(lobby, guestSession.getId(), displayName, now));

        return lobbySummary(lobby);
    }

    public Map<String, Object> getLobby(String code) {
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));
        return lobbySummary(lobby);
    }

    public Map<String, Object> joinLobby(HttpServletRequest request, String code, String rawPassword) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        Lobby lobby = lobbyRepository.findByCode(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        if (lobby.hasPassword()) {
            if (rawPassword == null || rawPassword.isBlank() || !passwordEncoder.matches(rawPassword, lobby.getPasswordHash())) {
                throw new ResponseStatusException(FORBIDDEN, "Invalid lobby password");
            }
        }

        if (participantRepository.existsByLobbyIdAndGuestSessionId(lobby.getId(), guestSession.getId())) {
            return lobbySummary(lobby);
        }

        long count = participantRepository.countByLobbyId(lobby.getId());
        if (count >= lobby.getMaxPlayers()) {
            throw new ResponseStatusException(CONFLICT, "Lobby is full");
        }

        Instant now = Instant.now();
        String displayName = uniqueDisplayNameForLobby(lobby.getId(), guestSession.getDisplayName());
        participantRepository.save(LobbyParticipant.createGuest(lobby, guestSession.getId(), displayName, now));

        return lobbySummary(lobby);
    }

    private Map<String, Object> lobbySummary(Lobby lobby) {
        List<LobbyParticipant> participants = participantRepository.findAllByLobbyIdOrderByJoinedAtAsc(lobby.getId());
        return Map.of(
                "code", lobby.getCode(),
                "status", lobby.getStatus().name(),
                "maxPlayers", lobby.getMaxPlayers(),
                "players", participants.stream().map(p -> Map.of(
                        "displayName", p.getDisplayName(),
                        "joinedAt", p.getJoinedAt().toString()
                )).toList(),
                "hasPassword", lobby.hasPassword(),
                "createdAt", lobby.getCreatedAt().toString()
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
}
