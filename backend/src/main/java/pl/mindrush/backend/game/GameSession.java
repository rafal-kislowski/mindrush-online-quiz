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
    @Column(name = "mode", length = 32, nullable = false)
    private GameSessionMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private GameStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 16, nullable = false)
    private GameStage stage;

    @Column(name = "stage_ends_at")
    private Instant stageEndsAt;

    @Column(name = "question_duration_ms")
    private Integer questionDurationMs;

    @Column(name = "question_pool_category_id")
    private Long questionPoolCategoryId;

    @Column(name = "xp_rewards_enabled")
    private Boolean xpRewardsEnabled;

    @Column(name = "coins_rewards_enabled")
    private Boolean coinsRewardsEnabled;

    @Column(name = "rank_points_rewards_enabled")
    private Boolean rankPointsRewardsEnabled;

    @Column(name = "lives_remaining")
    private Integer livesRemaining;

    @Column(name = "current_question_index", nullable = false)
    private int currentQuestionIndex;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "finish_reason", length = 24)
    private GameFinishReason finishReason;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @Column(name = "rewards_applied", nullable = false, columnDefinition = "boolean not null default false")
    private boolean rewardsApplied;

    @Column(name = "rewards_applied_at")
    private Instant rewardsAppliedAt;

    protected GameSession() {
    }

    public static GameSession startNew(
            String lobbyId,
            Long quizId,
            GameSessionMode mode,
            Instant now,
            Duration preCountdownDuration,
            Integer questionDurationMs,
            boolean xpRewardsEnabled,
            boolean coinsRewardsEnabled,
            boolean rankPointsRewardsEnabled
    ) {
        GameSession session = new GameSession();
        session.id = UUID.randomUUID().toString();
        session.lobbyId = lobbyId;
        session.quizId = quizId;
        session.mode = mode == null ? GameSessionMode.STANDARD : mode;
        session.status = GameStatus.IN_PROGRESS;
        session.stage = GameStage.PRE_COUNTDOWN;
        session.stageEndsAt = now.plus(preCountdownDuration);
        session.questionDurationMs = questionDurationMs;
        session.questionPoolCategoryId = null;
        session.xpRewardsEnabled = xpRewardsEnabled;
        session.coinsRewardsEnabled = coinsRewardsEnabled;
        session.rankPointsRewardsEnabled = rankPointsRewardsEnabled;
        session.livesRemaining = session.mode == GameSessionMode.THREE_LIVES ? 3 : null;
        session.currentQuestionIndex = 0;
        session.createdAt = now;
        session.startedAt = now;
        session.endedAt = null;
        session.finishReason = null;
        session.lastActivityAt = now;
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

    public GameSessionMode getMode() {
        return mode == null ? GameSessionMode.STANDARD : mode;
    }

    public void setMode(GameSessionMode mode) {
        this.mode = mode == null ? GameSessionMode.STANDARD : mode;
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

    public Long getQuestionPoolCategoryId() {
        return questionPoolCategoryId;
    }

    public void setQuestionPoolCategoryId(Long questionPoolCategoryId) {
        this.questionPoolCategoryId = questionPoolCategoryId;
    }

    public Boolean getXpRewardsEnabledRaw() {
        return xpRewardsEnabled;
    }

    public boolean isXpRewardsEnabled() {
        return Boolean.TRUE.equals(xpRewardsEnabled);
    }

    public void setXpRewardsEnabled(Boolean xpRewardsEnabled) {
        this.xpRewardsEnabled = xpRewardsEnabled;
    }

    public Boolean getCoinsRewardsEnabledRaw() {
        return coinsRewardsEnabled;
    }

    public boolean isCoinsRewardsEnabled() {
        return Boolean.TRUE.equals(coinsRewardsEnabled);
    }

    public void setCoinsRewardsEnabled(Boolean coinsRewardsEnabled) {
        this.coinsRewardsEnabled = coinsRewardsEnabled;
    }

    public Boolean getRankPointsRewardsEnabledRaw() {
        return rankPointsRewardsEnabled;
    }

    public boolean isRankPointsRewardsEnabled() {
        return Boolean.TRUE.equals(rankPointsRewardsEnabled);
    }

    public void setRankPointsRewardsEnabled(Boolean rankPointsRewardsEnabled) {
        this.rankPointsRewardsEnabled = rankPointsRewardsEnabled;
    }

    public Integer getLivesRemaining() {
        return livesRemaining;
    }

    public void setLivesRemaining(Integer livesRemaining) {
        this.livesRemaining = livesRemaining;
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

    public GameFinishReason getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(GameFinishReason finishReason) {
        this.finishReason = finishReason;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(Instant lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
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
