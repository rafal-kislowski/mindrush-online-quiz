package pl.mindrush.backend.game.events;

public record GameEventDto(
        String type,
        String lobbyCode,
        String serverTime,
        String lobbyStatus,
        String stage
) {
}
