package pl.mindrush.backend.game;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GamePlayerRepository extends JpaRepository<GamePlayer, Long> {
    boolean existsByGameSessionIdAndGuestSessionId(String gameSessionId, String guestSessionId);
    long countByGameSessionId(String gameSessionId);
    List<GamePlayer> findAllByGameSessionIdOrderByOrderIndexAsc(String gameSessionId);

    @Query("""
            select count(distinct gp.gameSession.id)
            from GamePlayer gp
            where gp.guestSessionId in :guestSessionIds
              and gp.gameSession.status = :status
            """)
    long countDistinctFinishedSessionsByGuestSessionIds(
            @Param("guestSessionIds") List<String> guestSessionIds,
            @Param("status") GameStatus status
    );

    @Query("""
            select count(distinct gp.gameSession.id)
            from GamePlayer gp
            where gp.guestSessionId in :guestSessionIds
              and gp.gameSession.status = :status
              and gp.gameSession.lobbyId like :lobbyIdPattern
            """)
    long countDistinctFinishedSessionsByGuestSessionIdsAndLobbyIdLike(
            @Param("guestSessionIds") List<String> guestSessionIds,
            @Param("status") GameStatus status,
            @Param("lobbyIdPattern") String lobbyIdPattern
    );

    @Query("""
            select count(distinct gp.gameSession.id)
            from GamePlayer gp
            where gp.guestSessionId in :guestSessionIds
              and gp.gameSession.status = :status
              and gp.gameSession.lobbyId not like :lobbyIdPattern
            """)
    long countDistinctFinishedSessionsByGuestSessionIdsAndLobbyIdNotLike(
            @Param("guestSessionIds") List<String> guestSessionIds,
            @Param("status") GameStatus status,
            @Param("lobbyIdPattern") String lobbyIdPattern
    );

    @Query("""
            select gs
            from GamePlayer gp
            join gp.gameSession gs
            where gp.guestSessionId = :guestSessionId
              and gs.status = :status
            order by gs.startedAt desc
            """)
    List<GameSession> findActiveSessionsByGuestSessionId(
            @Param("guestSessionId") String guestSessionId,
            @Param("status") GameStatus status
    );

    default Optional<GameSession> findFirstActiveSessionByGuestSessionId(
            String guestSessionId,
            GameStatus status
    ) {
        return findActiveSessionsByGuestSessionId(guestSessionId, status).stream().findFirst();
    }
}
