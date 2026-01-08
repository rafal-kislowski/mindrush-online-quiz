package pl.mindrush.backend.ws;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import pl.mindrush.backend.guest.GuestSessionRepository;

import java.time.Instant;
import java.util.Map;

@Component
public class GuestSessionHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_GUEST_SESSION_ID = "guestSessionId";

    private final GuestSessionRepository guestSessionRepository;

    public GuestSessionHandshakeInterceptor(GuestSessionRepository guestSessionRepository) {
        this.guestSessionRepository = guestSessionRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }

        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        Cookie[] cookies = httpRequest.getCookies();
        if (cookies == null) return false;

        String sessionId = null;
        for (Cookie cookie : cookies) {
            if ("guestSessionId".equals(cookie.getName())) {
                sessionId = cookie.getValue();
                break;
            }
        }
        if (sessionId == null || sessionId.isBlank()) return false;

        Instant now = Instant.now();
        boolean valid = guestSessionRepository.findById(sessionId)
                .filter(s -> !s.isRevoked())
                .filter(s -> s.getExpiresAt().isAfter(now))
                .isPresent();
        if (!valid) return false;

        attributes.put(ATTR_GUEST_SESSION_ID, sessionId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
    }
}

