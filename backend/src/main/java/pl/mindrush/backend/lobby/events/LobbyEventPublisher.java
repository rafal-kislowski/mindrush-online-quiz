package pl.mindrush.backend.lobby.events;

public interface LobbyEventPublisher {
    void lobbyUpdated(String lobbyCode);
    void participantKicked(String lobbyCode, String guestSessionId);
    void participantBanned(String lobbyCode, String guestSessionId);
}
