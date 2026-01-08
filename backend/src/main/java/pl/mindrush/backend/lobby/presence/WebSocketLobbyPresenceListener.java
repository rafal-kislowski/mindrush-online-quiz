package pl.mindrush.backend.lobby.presence;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import pl.mindrush.backend.lobby.LobbyService;

import java.security.Principal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebSocketLobbyPresenceListener {

    private static final Pattern DESTINATION_PATTERN = Pattern.compile("^/topic/lobbies/([A-Za-z0-9]{6})/(lobby|game)$");

    private final LobbyService lobbyService;
    private final ConcurrentHashMap<String, Presence> presenceBySessionId = new ConcurrentHashMap<>();

    public WebSocketLobbyPresenceListener(LobbyService lobbyService) {
        this.lobbyService = lobbyService;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        Principal user = accessor.getUser();
        if (destination == null || sessionId == null || user == null || user.getName() == null) return;

        Matcher matcher = DESTINATION_PATTERN.matcher(destination);
        if (!matcher.matches()) return;

        String lobbyCode = matcher.group(1).toUpperCase();
        presenceBySessionId.compute(sessionId, (key, existing) -> {
            if (existing == null) {
                Set<String> lobbyCodes = ConcurrentHashMap.newKeySet();
                lobbyCodes.add(lobbyCode);
                return new Presence(user.getName(), lobbyCodes);
            }
            existing.lobbyCodes.add(lobbyCode);
            return existing;
        });
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) return;

        Presence presence = presenceBySessionId.remove(sessionId);
        if (presence == null) return;

        for (String lobbyCode : presence.lobbyCodes) {
            lobbyService.handleGuestDisconnected(presence.guestSessionId, lobbyCode);
        }
    }

    private static final class Presence {
        private final String guestSessionId;
        private final Set<String> lobbyCodes;

        private Presence(String guestSessionId, Set<String> lobbyCodes) {
            this.guestSessionId = guestSessionId;
            this.lobbyCodes = lobbyCodes == null ? ConcurrentHashMap.newKeySet() : lobbyCodes;
        }
    }
}
