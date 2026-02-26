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
