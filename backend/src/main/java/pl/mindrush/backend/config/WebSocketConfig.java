package pl.mindrush.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import pl.mindrush.backend.ws.GuestSessionHandshakeHandler;
import pl.mindrush.backend.ws.GuestSessionHandshakeInterceptor;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final GuestSessionHandshakeInterceptor guestSessionHandshakeInterceptor;
    private final GuestSessionHandshakeHandler guestSessionHandshakeHandler;

    public WebSocketConfig(
            GuestSessionHandshakeInterceptor guestSessionHandshakeInterceptor,
            GuestSessionHandshakeHandler guestSessionHandshakeHandler
    ) {
        this.guestSessionHandshakeInterceptor = guestSessionHandshakeInterceptor;
        this.guestSessionHandshakeHandler = guestSessionHandshakeHandler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(guestSessionHandshakeInterceptor)
                .setHandshakeHandler(guestSessionHandshakeHandler);
    }
}

