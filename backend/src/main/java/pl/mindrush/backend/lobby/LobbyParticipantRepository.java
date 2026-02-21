package pl.mindrush.backend.lobby;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LobbyParticipantRepository extends JpaRepository<LobbyParticipant, Long> {
    long countByLobbyId(String lobbyId);
    boolean existsByLobbyIdAndGuestSessionId(String lobbyId, String guestSessionId);
    Optional<LobbyParticipant> findByLobbyIdAndGuestSessionId(String lobbyId, String guestSessionId);
    List<LobbyParticipant> findAllByLobbyIdOrderByJoinedAtAsc(String lobbyId);
    List<LobbyParticipant> findAllByLobbyIdInOrderByLobbyIdAscJoinedAtAsc(List<String> lobbyIds);
    long deleteByLobbyIdAndGuestSessionId(String lobbyId, String guestSessionId);
    List<LobbyParticipant> findAllByGuestSessionIdIn(List<String> guestSessionIds);

    @Query("""
            select p
            from LobbyParticipant p
            join fetch p.lobby l
            where p.guestSessionId = :guestSessionId
              and l.status in :statuses
            order by p.joinedAt desc
            """)
    List<LobbyParticipant> findActiveParticipationsByGuestSessionId(
            @Param("guestSessionId") String guestSessionId,
            @Param("statuses") List<LobbyStatus> statuses
    );

    @Modifying
    @Query("""
            update LobbyParticipant p
               set p.ready = false
             where p.lobby.id = :lobbyId
            """)
    void clearReadyByLobbyId(@Param("lobbyId") String lobbyId);
}
