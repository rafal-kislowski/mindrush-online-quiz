package pl.mindrush.backend.lobby.chat;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LobbyChatHistoryService {

    private static final int MAX_MESSAGES_PER_LOBBY = 2000;
    private static final Duration RETENTION = Duration.ofHours(12);

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<StoredMessage>> messagesByLobbyCode = new ConcurrentHashMap<>();

    public LobbyChatHistoryService(Clock clock) {
        this.clock = clock;
    }

    public LobbyChatMessageDto append(
            String lobbyCode,
            String displayName,
            String text,
            Instant serverTime
    ) {
        String normalizedCode = normalizeCode(lobbyCode);
        if (normalizedCode.isBlank()) {
            throw new IllegalArgumentException("lobbyCode is required");
        }

        Instant ts = serverTime == null ? clock.instant() : serverTime;
        String safeDisplayName = (displayName == null || displayName.isBlank()) ? "Guest" : displayName.trim();
        String safeText = text == null ? "" : text.trim();

        LobbyChatMessageDto dto = new LobbyChatMessageDto(
                normalizedCode,
                safeDisplayName,
                safeText,
                ts.toString()
        );

        Deque<StoredMessage> deque = messagesByLobbyCode.computeIfAbsent(normalizedCode, __ -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new StoredMessage(ts, dto));
            while (deque.size() > MAX_MESSAGES_PER_LOBBY) {
                deque.removeFirst();
            }
        }

        pruneExpired(ts);
        return dto;
    }

    public List<LobbyChatMessageDto> historySince(String lobbyCode, Instant sinceInclusive) {
        String normalizedCode = normalizeCode(lobbyCode);
        if (normalizedCode.isBlank()) return List.of();

        Instant threshold = sinceInclusive == null ? Instant.EPOCH : sinceInclusive;
        Instant now = clock.instant();
        pruneExpired(now);

        Deque<StoredMessage> deque = messagesByLobbyCode.get(normalizedCode);
        if (deque == null) return List.of();

        List<LobbyChatMessageDto> out = new ArrayList<>();
        synchronized (deque) {
            for (StoredMessage stored : deque) {
                if (stored.serverTime.isBefore(threshold)) continue;
                out.add(stored.dto);
            }
        }
        return out;
    }

    private void pruneExpired(Instant now) {
        Instant cutoff = now.minus(RETENTION);
        for (Map.Entry<String, Deque<StoredMessage>> entry : messagesByLobbyCode.entrySet()) {
            Deque<StoredMessage> deque = entry.getValue();
            boolean empty;
            synchronized (deque) {
                while (!deque.isEmpty() && deque.peekFirst().serverTime.isBefore(cutoff)) {
                    deque.removeFirst();
                }
                empty = deque.isEmpty();
            }
            if (empty) {
                messagesByLobbyCode.remove(entry.getKey(), deque);
            }
        }
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    private record StoredMessage(Instant serverTime, LobbyChatMessageDto dto) {
    }
}

