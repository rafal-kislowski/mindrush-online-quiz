package pl.mindrush.backend.lobby.events;

import java.util.Map;

public record LobbyEventDto(
        String type,
        String lobbyCode,
        String serverTime,
        Map<String, Object> state
) {
}
