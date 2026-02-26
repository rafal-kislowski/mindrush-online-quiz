package pl.mindrush.backend.game.dto;

public record ActiveGameDto(
        String type,
        String gameSessionId,
        String lobbyCode
) {
}
