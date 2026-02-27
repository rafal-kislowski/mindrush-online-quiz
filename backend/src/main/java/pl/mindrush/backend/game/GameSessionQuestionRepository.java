package pl.mindrush.backend.game;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameSessionQuestionRepository extends JpaRepository<GameSessionQuestion, Long> {
    long countByGameSessionId(String gameSessionId);

    Optional<GameSessionQuestion> findByGameSessionIdAndOrderIndex(String gameSessionId, int orderIndex);
}
