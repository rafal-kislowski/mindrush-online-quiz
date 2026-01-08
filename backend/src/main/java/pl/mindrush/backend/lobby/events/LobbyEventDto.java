package pl.mindrush.backend.lobby.events;

public record LobbyEventDto(
        String type,
        String lobbyCode,
        String serverTime
) {
}

