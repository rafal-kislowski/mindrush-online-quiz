package pl.mindrush.backend.game;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GameAnswerRepository extends JpaRepository<GameAnswer, Long> {

    boolean existsByGameSessionIdAndQuestionIdAndGuestSessionId(String gameSessionId, Long questionId, String guestSessionId);

    long countByGameSessionIdAndQuestionId(String gameSessionId, Long questionId);

    List<GameAnswer> findAllByGameSessionIdAndQuestionId(String gameSessionId, Long questionId);

    Optional<GameAnswer> findByGameSessionIdAndQuestionIdAndGuestSessionId(String gameSessionId, Long questionId, String guestSessionId);

    @Query("select count(a.id) from GameAnswer a where a.gameSession.id = :gameSessionId and a.guestSessionId = :guestSessionId and a.correct = true")
    long countCorrectByGameSessionIdAndGuestSessionId(@Param("gameSessionId") String gameSessionId, @Param("guestSessionId") String guestSessionId);
}

