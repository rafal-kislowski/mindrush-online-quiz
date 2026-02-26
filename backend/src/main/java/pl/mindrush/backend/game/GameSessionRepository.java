package pl.mindrush.backend.game;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GameSessionRepository extends JpaRepository<GameSession, String> {
    Optional<GameSession> findFirstByLobbyIdAndStatusOrderByStartedAtDesc(String lobbyId, GameStatus status);

    Optional<GameSession> findFirstByLobbyIdOrderByStartedAtDesc(String lobbyId);

    List<GameSession> findAllByStatusAndStageEndsAtBefore(GameStatus status, Instant stageEndsAt);

    @Query("""
            select gs
            from GameSession gs
            where gs.status = :status
              and gs.mode = :mode
              and (
                    (gs.lastActivityAt is not null and gs.lastActivityAt <= :cutoff)
                    or
                    (gs.lastActivityAt is null and gs.startedAt <= :cutoff)
              )
            """)
    List<GameSession> findStaleSessionsByModeAndStatus(
            @Param("mode") GameSessionMode mode,
            @Param("status") GameStatus status,
            @Param("cutoff") Instant cutoff
    );

    long countByStatus(GameStatus status);
}
