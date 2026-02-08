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

    @Column(name = "question_duration_ms")
    private Integer questionDurationMs;

    @Column(name = "current_question_index", nullable = false)
    private int currentQuestionIndex;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "rewards_applied", nullable = false, columnDefinition = "boolean not null default false")
    private boolean rewardsApplied;

    @Column(name = "rewards_applied_at")
    private Instant rewardsAppliedAt;

    protected GameSession() {
    }

    public static GameSession startNew(
            String lobbyId,
            Long quizId,
            Instant now,
            Duration preCountdownDuration,
            Integer questionDurationMs
    ) {
        GameSession session = new GameSession();
        session.id = UUID.randomUUID().toString();
        session.lobbyId = lobbyId;
        session.quizId = quizId;
        session.status = GameStatus.IN_PROGRESS;
        session.stage = GameStage.PRE_COUNTDOWN;
        session.stageEndsAt = now.plus(preCountdownDuration);
        session.questionDurationMs = questionDurationMs;
        session.currentQuestionIndex = 0;
        session.createdAt = now;
        session.startedAt = now;
        session.endedAt = null;
        session.rewardsApplied = false;
        session.rewardsAppliedAt = null;
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

    public Integer getQuestionDurationMs() {
        return questionDurationMs;
    }

    public void setQuestionDurationMs(Integer questionDurationMs) {
        this.questionDurationMs = questionDurationMs;
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

    public boolean isRewardsApplied() {
        return rewardsApplied;
    }

    public void setRewardsApplied(boolean rewardsApplied) {
        this.rewardsApplied = rewardsApplied;
    }

    public Instant getRewardsAppliedAt() {
        return rewardsAppliedAt;
    }

    public void setRewardsAppliedAt(Instant rewardsAppliedAt) {
        this.rewardsAppliedAt = rewardsAppliedAt;
    }
}
