package pl.mindrush.backend.lobby.chat;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class LobbySystemMessageService {

    private final LobbyChatHistoryService chatHistoryService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public LobbySystemMessageService(
            LobbyChatHistoryService chatHistoryService,
            SimpMessagingTemplate messagingTemplate,
            Clock clock
    ) {
        this.chatHistoryService = chatHistoryService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    public LobbyChatMessageDto publish(String lobbyCode, String text) {
        String safeText = text == null ? "" : text.trim();
        if (safeText.isBlank()) return null;

        LobbyChatMessageDto message = chatHistoryService.appendSystem(
                lobbyCode,
                safeText,
                clock.instant()
        );
        messagingTemplate.convertAndSend("/topic/lobbies/" + message.lobbyCode() + "/chat", message);
        return message;
    }
}
