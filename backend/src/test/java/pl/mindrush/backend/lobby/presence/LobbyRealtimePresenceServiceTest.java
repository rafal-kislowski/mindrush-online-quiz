package pl.mindrush.backend.lobby.presence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LobbyRealtimePresenceServiceTest {

    @Test
    void lobbyViewActive_togglesWithSubscribeAndUnsubscribe() {
        LobbyRealtimePresenceService service = new LobbyRealtimePresenceService();

        service.onSubscribe(
                "ws-1",
                "sub-1",
                "guest-1",
                "/topic/lobbies/AB12CD/lobby"
        );

        assertThat(service.isLobbyViewActive("guest-1", "AB12CD")).isTrue();

        service.onUnsubscribe("ws-1", "sub-1");

        assertThat(service.isLobbyViewActive("guest-1", "AB12CD")).isFalse();
    }

    @Test
    void disconnect_returnsTouchedLobbyCodesAndClearsLobbyViewPresence() {
        LobbyRealtimePresenceService service = new LobbyRealtimePresenceService();

        service.onSubscribe(
                "ws-1",
                "sub-lobby",
                "guest-2",
                "/topic/lobbies/ZX9YQ1/lobby"
        );
        service.onSubscribe(
                "ws-1",
                "sub-chat",
                "guest-2",
                "/topic/lobbies/ZX9YQ1/chat"
        );

        LobbyRealtimePresenceService.DisconnectPresence disconnect =
                service.onDisconnect("ws-1");

        assertThat(disconnect.isEmpty()).isFalse();
        assertThat(disconnect.guestSessionId()).isEqualTo("guest-2");
        assertThat(disconnect.lobbyCodes()).containsExactly("ZX9YQ1");
        assertThat(service.isLobbyViewActive("guest-2", "ZX9YQ1")).isFalse();
    }
}

