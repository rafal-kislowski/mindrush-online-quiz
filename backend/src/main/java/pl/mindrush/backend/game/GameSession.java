package pl.mindrush.backend.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_sessions")
public class GameSession {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "lobby_id", length = 36, nullable = false, updatable = false)
    private String lobbyId;

    @Column(name = "quiz_id", nullable = false, updatable = false)
    private Long quizId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private GameStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 16, nullable = false)
    private GameStage stage;

    @Column(name = "stage_ends_at", nullable = false)
    private Instant stageEndsAt;

    @Column(name = "current_question_index", nullable = false)
    private int currentQuestionIndex;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected GameSession() {
    }

    public static GameSession startNew(String lobbyId, Long quizId, Instant now, Duration questionDuration) {
        GameSession session = new GameSession();
        session.id = UUID.randomUUID().toString();
        session.lobbyId = lobbyId;
        session.quizId = quizId;
        session.status = GameStatus.IN_PROGRESS;
        session.stage = GameStage.QUESTION;
        session.stageEndsAt = now.plus(questionDuration);
        session.currentQuestionIndex = 0;
        session.createdAt = now;
        session.startedAt = now;
        session.endedAt = null;
        return session;
    }

    public String getId() {
        return id;
    }

    public String getLobbyId() {
        return lobbyId;
    }

    public Long getQuizId() {
        return quizId;
    }

    public GameStatus getStatus() {
        return status;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
    }

    public GameStage getStage() {
        return stage;
    }

    public void setStage(GameStage stage) {
        this.stage = stage;
    }

    public Instant getStageEndsAt() {
        return stageEndsAt;
    }

    public void setStageEndsAt(Instant stageEndsAt) {
        this.stageEndsAt = stageEndsAt;
    }

    public int getCurrentQuestionIndex() {
        return currentQuestionIndex;
    }

    public void setCurrentQuestionIndex(int currentQuestionIndex) {
        this.currentQuestionIndex = currentQuestionIndex;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }
}
