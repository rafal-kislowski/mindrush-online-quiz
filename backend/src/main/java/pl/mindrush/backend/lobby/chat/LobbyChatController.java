package pl.mindrush.backend.lobby.chat;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.guest.GuestSessionRepository;
import pl.mindrush.backend.lobby.Lobby;
import pl.mindrush.backend.lobby.LobbyParticipant;
import pl.mindrush.backend.lobby.LobbyParticipantRepository;
import pl.mindrush.backend.lobby.LobbyRepository;

import java.security.Principal;
import java.time.Clock;
import java.util.Optional;

@Controller
public class LobbyChatController {

    private static final int MAX_MESSAGE_LENGTH = 300;

    private final SimpMessagingTemplate messagingTemplate;
    private final LobbyRepository lobbyRepository;
    private final LobbyParticipantRepository participantRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final AppUserRepository appUserRepository;
    private final LobbyChatHistoryService chatHistoryService;
    private final Clock clock;

    public LobbyChatController(
            SimpMessagingTemplate messagingTemplate,
            LobbyRepository lobbyRepository,
            LobbyParticipantRepository participantRepository,
            GuestSessionRepository guestSessionRepository,
            AppUserRepository appUserRepository,
            LobbyChatHistoryService chatHistoryService,
            Clock clock
    ) {
        this.messagingTemplate = messagingTemplate;
        this.lobbyRepository = lobbyRepository;
        this.participantRepository = participantRepository;
        this.guestSessionRepository = guestSessionRepository;
        this.appUserRepository = appUserRepository;
        this.chatHistoryService = chatHistoryService;
        this.clock = clock;
    }

    @MessageMapping("/lobbies/{code}/chat")
    public void send(
            @DestinationVariable("code") String code,
            Principal principal,
            LobbyChatSendRequest request
    ) {
        if (principal == null || principal.getName() == null) return;
        if (request == null) return;

        String lobbyCode = (code == null ? "" : code.trim()).toUpperCase();
        if (lobbyCode.isBlank()) return;

        String rawText = request.text();
        String text = rawText == null ? "" : rawText.trim();
        if (text.isBlank()) return;
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH);
        }

        Optional<Lobby> lobbyOpt = lobbyRepository.findByCode(lobbyCode);
        if (lobbyOpt.isEmpty()) return;
        Lobby lobby = lobbyOpt.get();

        String guestSessionId = principal.getName();
        Optional<LobbyParticipant> participantOpt = participantRepository.findByLobbyIdAndGuestSessionId(lobby.getId(), guestSessionId);
        if (participantOpt.isEmpty()) return;

        LobbyParticipant participant = participantOpt.get();
        boolean premium = isPremiumGuestSession(guestSessionId);
        LobbyChatMessageDto msg = chatHistoryService.append(
                lobbyCode,
                participant.getDisplayName(),
                text,
                clock.instant(),
                premium
        );
        messagingTemplate.convertAndSend("/topic/lobbies/" + lobbyCode + "/chat", msg);
    }

    private boolean isPremiumGuestSession(String guestSessionId) {
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

    public record LobbyChatSendRequest(String text) {
    }
}
