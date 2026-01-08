package pl.mindrush.backend.game;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "game.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class GameTimeoutScheduler {

    private final GameService gameService;

    public GameTimeoutScheduler(GameService gameService) {
        this.gameService = gameService;
    }

    @Scheduled(fixedDelayString = "${game.scheduler.fixedDelayMs:250}")
    public void tick() {
        gameService.tickDueSessions();
    }
}

