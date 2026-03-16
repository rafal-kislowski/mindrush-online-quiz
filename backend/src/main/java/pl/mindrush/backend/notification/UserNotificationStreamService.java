package pl.mindrush.backend.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class UserNotificationStreamService {

    private static final Logger log = LoggerFactory.getLogger(UserNotificationStreamService.class);
    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L;
    private final Map<Long, List<SseEmitter>> emittersByUserId = new ConcurrentHashMap<>();

    public SseEmitter open(Long userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emittersByUserId.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        emitter.onError(ex -> removeEmitter(userId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(new StreamEvent("connected", 0, Instant.now().toString())));
        } catch (Exception ex) {
            handleEmitterSendFailure(userId, emitter, ex, "connected");
        }
        return emitter;
    }

    public void publishRefresh(Long userId, long unreadCount) {
        List<SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null || emitters.isEmpty()) return;

        StreamEvent event = new StreamEvent("refresh", unreadCount, Instant.now().toString());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("refresh").data(event));
            } catch (Exception ex) {
                handleEmitterSendFailure(userId, emitter, ex, "refresh");
            }
        }
    }

    private void removeEmitter(Long userId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByUserId.remove(userId);
        }
    }

    private void handleEmitterSendFailure(Long userId, SseEmitter emitter, Exception ex, String eventName) {
        String message = ex == null || ex.getMessage() == null ? "-" : ex.getMessage();
        if (isClientDisconnect(ex)) {
            log.debug("SSE client disconnected (userId={}, event={}, reason={})", userId, eventName, message);
        } else {
            log.warn("SSE emitter send failed (userId={}, event={}, reason={})", userId, eventName, message);
        }
        removeEmitter(userId, emitter);
    }

    private static boolean isClientDisconnect(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < 8) {
            if (current instanceof IOException) {
                String message = current.getMessage();
                if (message != null) {
                    String normalized = message.toLowerCase();
                    if (normalized.contains("broken pipe")
                            || normalized.contains("connection reset")
                            || normalized.contains("forcibly closed")
                            || normalized.contains("przerwane przez oprogramowanie")) {
                        return true;
                    }
                }
            }

            String className = current.getClass().getName();
            if (className.contains("ClientAbortException")
                    || className.contains("EOFException")
                    || className.contains("EofException")) {
                return true;
            }

            current = current.getCause();
            depth++;
        }
        return false;
    }

    public record StreamEvent(String type, long unreadCount, String ts) {}
}
