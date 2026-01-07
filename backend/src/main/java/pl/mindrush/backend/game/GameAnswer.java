package pl.mindrush.backend.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "game_answers",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_game_answer_once",
                columnNames = {"game_session_id", "question_id", "guest_session_id"}
        )
)
public class GameAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_session_id", nullable = false, updatable = false)
    private GameSession gameSession;

    @Column(name = "question_id", nullable = false, updatable = false)
    private Long questionId;

    @Column(name = "guest_session_id", length = 36, nullable = false, updatable = false)
    private String guestSessionId;

    @Column(name = "selected_option_id")
    private Long selectedOptionId;

    @Column(name = "correct", nullable = false, updatable = false)
    private boolean correct;

    @Column(name = "answered_at", nullable = false, updatable = false)
    private Instant answeredAt;

    protected GameAnswer() {
    }

    public static GameAnswer create(GameSession session, Long questionId, String guestSessionId, Long selectedOptionId, boolean correct, Instant now) {
        GameAnswer a = new GameAnswer();
        a.gameSession = session;
        a.questionId = questionId;
        a.guestSessionId = guestSessionId;
        a.selectedOptionId = selectedOptionId;
        a.correct = correct;
        a.answeredAt = now;
        return a;
    }

    public Long getId() {
        return id;
    }

    public GameSession getGameSession() {
        return gameSession;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getGuestSessionId() {
        return guestSessionId;
    }

    public Long getSelectedOptionId() {
        return selectedOptionId;
    }

    public boolean isCorrect() {
        return correct;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }
}
