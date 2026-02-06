package pl.mindrush.backend.game;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GameSessionRepository extends JpaRepository<GameSession, String> {
    Optional<GameSession> findFirstByLobbyIdAndStatusOrderByStartedAtDesc(String lobbyId, GameStatus status);

    Optional<GameSession> findFirstByLobbyIdOrderByStartedAtDesc(String lobbyId);

    List<GameSession> findAllByStatusAndStageEndsAtBefore(GameStatus status, Instant stageEndsAt);

    long countByStatus(GameStatus status);
}
