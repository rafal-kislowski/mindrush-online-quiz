package pl.mindrush.backend.lobby.chat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionRepository;
import pl.mindrush.backend.guest.GuestSessionService;
import pl.mindrush.backend.lobby.Lobby;
import pl.mindrush.backend.lobby.LobbyParticipant;
import pl.mindrush.backend.lobby.LobbyParticipantRepository;
import pl.mindrush.backend.lobby.LobbyRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/lobbies/{code}/chat")
public class LobbyChatRestController {

    private static final int MAX_MESSAGE_LENGTH = 300;

    private final GuestSessionService guestSessionService;
    private final GuestSessionRepository guestSessionRepository;
    private final AppUserRepository appUserRepository;
    private final LobbyRepository lobbyRepository;
    private final LobbyParticipantRepository participantRepository;
    private final LobbyChatHistoryService chatHistoryService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public LobbyChatRestController(
            GuestSessionService guestSessionService,
            GuestSessionRepository guestSessionRepository,
            AppUserRepository appUserRepository,
            LobbyRepository lobbyRepository,
            LobbyParticipantRepository participantRepository,
            LobbyChatHistoryService chatHistoryService,
            SimpMessagingTemplate messagingTemplate,
            Clock clock
    ) {
        this.guestSessionService = guestSessionService;
        this.guestSessionRepository = guestSessionRepository;
        this.appUserRepository = appUserRepository;
        this.lobbyRepository = lobbyRepository;
        this.participantRepository = participantRepository;
        this.chatHistoryService = chatHistoryService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    @GetMapping
    public ResponseEntity<List<LobbyChatMessageDto>> history(
            HttpServletRequest request,
            @PathVariable String code
    ) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        String lobbyCode = normalizeCode(code);
        if (lobbyCode.isBlank()) {
            throw new ResponseStatusException(NOT_FOUND, "Lobby not found");
        }

        Lobby lobby = lobbyRepository.findByCode(lobbyCode)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        Optional<LobbyParticipant> participantOpt =
                participantRepository.findByLobbyIdAndGuestSessionId(lobby.getId(), guestSession.getId());
        if (participantOpt.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        Instant joinedAt = participantOpt.get().getJoinedAt();
        return ResponseEntity.ok(chatHistoryService.historySince(lobbyCode, joinedAt));
    }

    @PostMapping
    public ResponseEntity<LobbyChatMessageDto> send(
            HttpServletRequest request,
            @PathVariable String code,
            @Valid @RequestBody(required = false) LobbyChatSendRequest body
    ) {
        GuestSession guestSession = guestSessionService.requireValidSession(request);
        String lobbyCode = normalizeCode(code);
        if (lobbyCode.isBlank()) {
            throw new ResponseStatusException(NOT_FOUND, "Lobby not found");
        }

        Lobby lobby = lobbyRepository.findByCode(lobbyCode)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lobby not found"));

        LobbyParticipant participant = participantRepository.findByLobbyIdAndGuestSessionId(lobby.getId(), guestSession.getId())
                .orElseThrow(() -> new ResponseStatusException(FORBIDDEN, "Only participants can send chat messages"));

        String rawText = body == null ? null : body.text();
        String text = rawText == null ? "" : rawText.trim();
        if (text.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Message cannot be empty");
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH);
        }

        boolean premium = isPremiumGuestSessionId(guestSession.getId());
        LobbyChatMessageDto message = chatHistoryService.append(
                lobbyCode,
                participant.getDisplayName(),
                text,
                clock.instant(),
                premium
        );
        messagingTemplate.convertAndSend("/topic/lobbies/" + lobbyCode + "/chat", message);
        return ResponseEntity.ok(message);
    }

    private boolean isPremiumGuestSessionId(String guestSessionId) {
        if (guestSessionId == null || guestSessionId.isBlank()) return false;
        return guestSessionRepository.findById(guestSessionId)
                .map(session -> {
                    Long userId = session.getUserId();
                    if (userId == null) return false;
                    return appUserRepository.findById(userId)
                            .map(user -> user.getRoles().contains(AppRole.PREMIUM))
                            .orElse(false);
                })
                .orElse(false);
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    public record LobbyChatSendRequest(String text) {
    }
}
