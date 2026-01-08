package pl.mindrush.backend.ws;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Component
public class GuestSessionHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Object guestSessionId = attributes.get(GuestSessionHandshakeInterceptor.ATTR_GUEST_SESSION_ID);
        if (guestSessionId instanceof String id && !id.isBlank()) {
            return new GuestPrincipal(id);
        }
        return new GuestPrincipal("anonymous-" + UUID.randomUUID());
    }

    private record GuestPrincipal(String name) implements Principal {
        @Override
        @Nullable
        public String getName() {
            return name;
        }
    }
}

