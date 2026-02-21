package pl.mindrush.backend.lobby.presence;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LobbyRealtimePresenceService {

    private static final Pattern DESTINATION_PATTERN =
            Pattern.compile("^/(?:topic|user/queue)/lobbies/([A-Za-z0-9]{6})/(lobby|game|chat)$");

    private final ConcurrentHashMap<String, SubscriptionInfo> subscriptionsByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> subscriptionKeysBySessionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> lobbyViewCountersByGuestAndLobby = new ConcurrentHashMap<>();

    public TrackedSubscription onSubscribe(
            String sessionId,
            String subscriptionId,
            String guestSessionId,
            String destination
    ) {
        Destination parsed = parseDestination(destination);
        if (parsed == null) return null;
        if (sessionId == null || sessionId.isBlank()) return null;
        if (subscriptionId == null || subscriptionId.isBlank()) return null;
        if (guestSessionId == null || guestSessionId.isBlank()) return null;

        String subscriptionKey = subscriptionKey(sessionId, subscriptionId);
        SubscriptionInfo next = new SubscriptionInfo(
                sessionId,
                guestSessionId,
                parsed.lobbyCode(),
                parsed.channel()
        );

        SubscriptionInfo previous = subscriptionsByKey.put(subscriptionKey, next);
        if (previous != null) {
            unregisterFromSession(previous.sessionId(), subscriptionKey);
            decrementLobbyViewCounter(previous);
        }

        subscriptionKeysBySessionId
                .computeIfAbsent(sessionId, __ -> ConcurrentHashMap.newKeySet())
                .add(subscriptionKey);
        incrementLobbyViewCounter(next);

        return new TrackedSubscription(parsed.lobbyCode(), parsed.channel());
    }

    public void onUnsubscribe(String sessionId, String subscriptionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (subscriptionId == null || subscriptionId.isBlank()) return;

        String subscriptionKey = subscriptionKey(sessionId, subscriptionId);
        SubscriptionInfo removed = subscriptionsByKey.remove(subscriptionKey);
        if (removed == null) return;

        unregisterFromSession(sessionId, subscriptionKey);
        decrementLobbyViewCounter(removed);
    }

    public DisconnectPresence onDisconnect(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return DisconnectPresence.empty();

        Set<String> subscriptionKeys = subscriptionKeysBySessionId.remove(sessionId);
        if (subscriptionKeys == null || subscriptionKeys.isEmpty()) return DisconnectPresence.empty();

        String guestSessionId = null;
        Set<String> lobbyCodes = new HashSet<>();

        for (String key : subscriptionKeys) {
            SubscriptionInfo info = subscriptionsByKey.remove(key);
            if (info == null) continue;
            if (guestSessionId == null) {
                guestSessionId = info.guestSessionId();
            }
            lobbyCodes.add(info.lobbyCode());
            decrementLobbyViewCounter(info);
        }

        if (guestSessionId == null || guestSessionId.isBlank() || lobbyCodes.isEmpty()) {
            return DisconnectPresence.empty();
        }
        return new DisconnectPresence(guestSessionId, Set.copyOf(lobbyCodes));
    }

    public boolean isLobbyViewActive(String guestSessionId, String lobbyCode) {
        if (guestSessionId == null || guestSessionId.isBlank()) return false;
        if (lobbyCode == null || lobbyCode.isBlank()) return false;
        String key = guestLobbyKey(guestSessionId, lobbyCode);
        return lobbyViewCountersByGuestAndLobby.getOrDefault(key, 0) > 0;
    }

    private static Destination parseDestination(String destination) {
        if (destination == null || destination.isBlank()) return null;
        Matcher matcher = DESTINATION_PATTERN.matcher(destination);
        if (!matcher.matches()) return null;

        String lobbyCode = matcher.group(1).toUpperCase();
        Channel channel = Channel.valueOf(matcher.group(2).toUpperCase());
        return new Destination(lobbyCode, channel);
    }

    private static String subscriptionKey(String sessionId, String subscriptionId) {
        return sessionId + ":" + subscriptionId;
    }

    private static String guestLobbyKey(String guestSessionId, String lobbyCode) {
        return guestSessionId + "|" + lobbyCode.toUpperCase();
    }

    private void incrementLobbyViewCounter(SubscriptionInfo info) {
        if (info == null || info.channel() != Channel.LOBBY) return;
        String key = guestLobbyKey(info.guestSessionId(), info.lobbyCode());
        lobbyViewCountersByGuestAndLobby.merge(key, 1, Integer::sum);
    }

    private void decrementLobbyViewCounter(SubscriptionInfo info) {
        if (info == null || info.channel() != Channel.LOBBY) return;
        String key = guestLobbyKey(info.guestSessionId(), info.lobbyCode());
        lobbyViewCountersByGuestAndLobby.computeIfPresent(
                key,
                (__, count) -> count <= 1 ? null : count - 1
        );
    }

    private void unregisterFromSession(String sessionId, String subscriptionKey) {
        subscriptionKeysBySessionId.computeIfPresent(sessionId, (__, keys) -> {
            keys.remove(subscriptionKey);
            return keys.isEmpty() ? null : keys;
        });
    }

    public enum Channel {
        LOBBY,
        GAME,
        CHAT
    }

    public record TrackedSubscription(String lobbyCode, Channel channel) {
    }

    public record DisconnectPresence(String guestSessionId, Set<String> lobbyCodes) {
        public static DisconnectPresence empty() {
            return new DisconnectPresence(null, Set.of());
        }

        public boolean isEmpty() {
            return guestSessionId == null || guestSessionId.isBlank() || lobbyCodes == null || lobbyCodes.isEmpty();
        }
    }

    private record Destination(String lobbyCode, Channel channel) {
    }

    private record SubscriptionInfo(
            String sessionId,
            String guestSessionId,
            String lobbyCode,
            Channel channel
    ) {
    }
}

