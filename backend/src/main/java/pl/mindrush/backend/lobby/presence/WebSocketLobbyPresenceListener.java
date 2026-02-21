package pl.mindrush.backend.lobby.presence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;
import pl.mindrush.backend.lobby.LobbyService;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketLobbyPresenceListener {

    private final LobbyService lobbyService;
    private final LobbyRealtimePresenceService realtimePresenceService;
    private final Duration reconnectGrace;
    private final ConcurrentHashMap<String, PendingDisconnectRemoval> pendingDisconnectRemovals = new ConcurrentHashMap<>();

    public WebSocketLobbyPresenceListener(
            LobbyService lobbyService,
            LobbyRealtimePresenceService realtimePresenceService,
            @Value("${lobby.presence.reconnect-grace:PT45S}") Duration reconnectGrace
    ) {
        this.lobbyService = lobbyService;
        this.realtimePresenceService = realtimePresenceService;
        this.reconnectGrace = reconnectGrace;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        Principal user = accessor.getUser();
        if (destination == null || sessionId == null || subscriptionId == null || user == null || user.getName() == null) return;

        LobbyRealtimePresenceService.TrackedSubscription tracked = realtimePresenceService
                .onSubscribe(sessionId, subscriptionId, user.getName(), destination);
        if (tracked == null) return;
        cancelPendingRemoval(user.getName(), tracked.lobbyCode());
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (sessionId == null || subscriptionId == null) return;
        realtimePresenceService.onUnsubscribe(sessionId, subscriptionId);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) return;

        LobbyRealtimePresenceService.DisconnectPresence presence =
                realtimePresenceService.onDisconnect(sessionId);
        if (presence == null || presence.isEmpty()) return;

        for (String lobbyCode : presence.lobbyCodes()) {
            schedulePendingRemoval(presence.guestSessionId(), lobbyCode);
        }
    }

    @Scheduled(fixedDelayString = "${lobby.presence.reconnect-grace.cleanup.fixedDelayMs:2000}")
    public void processPendingDisconnectRemovals() {
        if (pendingDisconnectRemovals.isEmpty()) return;

        Instant now = Instant.now();
        for (Map.Entry<String, PendingDisconnectRemoval> entry : pendingDisconnectRemovals.entrySet()) {
            PendingDisconnectRemoval pending = entry.getValue();
            if (pending == null || pending.executeAt().isAfter(now)) continue;
            if (!pendingDisconnectRemovals.remove(entry.getKey(), pending)) continue;
            lobbyService.handleGuestDisconnected(pending.guestSessionId(), pending.lobbyCode());
        }
    }

    private void schedulePendingRemoval(String guestSessionId, String lobbyCode) {
        if (guestSessionId == null || guestSessionId.isBlank()) return;
        if (lobbyCode == null || lobbyCode.isBlank()) return;
        String key = pendingRemovalKey(guestSessionId, lobbyCode);
        pendingDisconnectRemovals.put(
                key,
                new PendingDisconnectRemoval(guestSessionId, lobbyCode, Instant.now().plus(reconnectGrace))
        );
    }

    private void cancelPendingRemoval(String guestSessionId, String lobbyCode) {
        if (guestSessionId == null || guestSessionId.isBlank()) return;
        if (lobbyCode == null || lobbyCode.isBlank()) return;
        pendingDisconnectRemovals.remove(pendingRemovalKey(guestSessionId, lobbyCode));
    }

    private static String pendingRemovalKey(String guestSessionId, String lobbyCode) {
        return guestSessionId + "|" + lobbyCode;
    }

    private record PendingDisconnectRemoval(
            String guestSessionId,
            String lobbyCode,
            Instant executeAt
    ) {
    }
}
