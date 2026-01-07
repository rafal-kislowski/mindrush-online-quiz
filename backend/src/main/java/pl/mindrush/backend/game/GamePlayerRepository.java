package pl.mindrush.backend.game;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GamePlayerRepository extends JpaRepository<GamePlayer, Long> {
    boolean existsByGameSessionIdAndGuestSessionId(String gameSessionId, String guestSessionId);
    long countByGameSessionId(String gameSessionId);
    List<GamePlayer> findAllByGameSessionIdOrderByOrderIndexAsc(String gameSessionId);
}

