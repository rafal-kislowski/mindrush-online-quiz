package pl.mindrush.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(createWebSocketBrokerTaskScheduler())
                .setHeartbeatValue(new long[]{10_000, 10_000});
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(guestSessionHandshakeInterceptor)
                .setHandshakeHandler(guestSessionHandshakeHandler);
    }

    private TaskScheduler createWebSocketBrokerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-broker-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}
