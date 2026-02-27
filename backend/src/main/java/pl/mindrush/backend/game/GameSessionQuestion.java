package pl.mindrush.backend.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "game_session_questions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_game_session_questions_order", columnNames = {"game_session_id", "order_index"})
        },
        indexes = {
                @Index(name = "idx_game_session_questions_session", columnList = "game_session_id")
        }
)
public class GameSessionQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_session_id", length = 36, nullable = false, updatable = false)
    private String gameSessionId;

    @Column(name = "question_id", nullable = false, updatable = false)
    private Long questionId;

    @Column(name = "order_index", nullable = false, updatable = false)
    private int orderIndex;

    protected GameSessionQuestion() {
    }

    public static GameSessionQuestion create(String gameSessionId, Long questionId, int orderIndex) {
        GameSessionQuestion row = new GameSessionQuestion();
        row.gameSessionId = gameSessionId;
        row.questionId = questionId;
        row.orderIndex = orderIndex;
        return row;
    }

    public Long getId() {
        return id;
    }

    public String getGameSessionId() {
        return gameSessionId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public int getOrderIndex() {
        return orderIndex;
    }
}
