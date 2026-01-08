package pl.mindrush.backend.game.events;

public interface GameEventPublisher {
    void gameUpdated(String lobbyCode);
}

