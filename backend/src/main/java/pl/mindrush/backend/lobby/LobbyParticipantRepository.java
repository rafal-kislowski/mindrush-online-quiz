package pl.mindrush.backend.lobby;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LobbyParticipantRepository extends JpaRepository<LobbyParticipant, Long> {
    long countByLobbyId(String lobbyId);
    boolean existsByLobbyIdAndGuestSessionId(String lobbyId, String guestSessionId);
    Optional<LobbyParticipant> findByLobbyIdAndGuestSessionId(String lobbyId, String guestSessionId);
    List<LobbyParticipant> findAllByLobbyIdOrderByJoinedAtAsc(String lobbyId);
    long deleteByLobbyIdAndGuestSessionId(String lobbyId, String guestSessionId);
    List<LobbyParticipant> findAllByGuestSessionIdIn(List<String> guestSessionIds);
}
