package pl.mindrush.backend.game;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameSessionRepository extends JpaRepository<GameSession, String> {
    Optional<GameSession> findFirstByLobbyIdAndStatusOrderByStartedAtDesc(String lobbyId, GameStatus status);
}

