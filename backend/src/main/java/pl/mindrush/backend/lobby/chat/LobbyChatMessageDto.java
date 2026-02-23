package pl.mindrush.backend.lobby.chat;

public record LobbyChatMessageDto(
        String lobbyCode,
        String displayName,
        String text,
        String serverTime,
        String kind
) {
}
