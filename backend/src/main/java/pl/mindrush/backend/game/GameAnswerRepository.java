package pl.mindrush.backend.game;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GameAnswerRepository extends JpaRepository<GameAnswer, Long> {

    boolean existsByGameSessionIdAndQuestionIdAndGuestSessionId(String gameSessionId, Long questionId, String guestSessionId);

    long countByGameSessionIdAndQuestionId(String gameSessionId, Long questionId);

    List<GameAnswer> findAllByGameSessionId(String gameSessionId);

    List<GameAnswer> findAllByGameSessionIdAndQuestionId(String gameSessionId, Long questionId);

    Optional<GameAnswer> findByGameSessionIdAndQuestionIdAndGuestSessionId(String gameSessionId, Long questionId, String guestSessionId);

    @Query("select count(a.id) from GameAnswer a where a.gameSession.id = :gameSessionId and a.guestSessionId = :guestSessionId and a.correct = true")
    long countCorrectByGameSessionIdAndGuestSessionId(@Param("gameSessionId") String gameSessionId, @Param("guestSessionId") String guestSessionId);

    @Query("select coalesce(sum(a.points), 0) from GameAnswer a where a.gameSession.id = :gameSessionId and a.guestSessionId = :guestSessionId")
    long sumPointsByGameSessionIdAndGuestSessionId(@Param("gameSessionId") String gameSessionId, @Param("guestSessionId") String guestSessionId);

    @Query("select count(a.id) from GameAnswer a where a.gameSession.id = :gameSessionId and a.guestSessionId = :guestSessionId")
    long countByGameSessionIdAndGuestSessionId(@Param("gameSessionId") String gameSessionId, @Param("guestSessionId") String guestSessionId);

    @Query("select count(a.id) from GameAnswer a where a.gameSession.id = :gameSessionId and a.guestSessionId = :guestSessionId and a.correct = false")
    long countWrongByGameSessionIdAndGuestSessionId(@Param("gameSessionId") String gameSessionId, @Param("guestSessionId") String guestSessionId);

    @Query("""
            select count(a.id)
            from GameAnswer a
            where a.guestSessionId in :guestSessionIds
              and a.gameSession.status = :status
              and a.correct = true
            """)
    long countCorrectByGuestSessionIdsAndSessionStatus(
            @Param("guestSessionIds") List<String> guestSessionIds,
            @Param("status") GameStatus status
    );
}
