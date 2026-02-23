package pl.mindrush.backend.lobby;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LobbyBanRepository extends JpaRepository<LobbyBan, Long> {
    boolean existsByLobbyIdAndGuestSessionId(String lobbyId, String guestSessionId);
    boolean existsByLobbyIdAndUserId(String lobbyId, Long userId);
    void deleteAllByLobbyIdIn(List<String> lobbyIds);
}
