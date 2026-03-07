package pl.mindrush.backend.quiz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "quizzes")
public class Quiz {

    public static final int DEFAULT_QUESTION_TIME_LIMIT_SECONDS = 15;
    public static final int DEFAULT_QUESTIONS_PER_GAME = 7;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "title", length = 120, nullable = false)
    private String title;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "avatar_image_url", length = 500)
    private String avatarImageUrl;

    @Column(name = "avatar_bg_start", length = 32)
    private String avatarBgStart;

    @Column(name = "avatar_bg_end", length = 32)
    private String avatarBgEnd;

    @Column(name = "avatar_text_color", length = 32)
    private String avatarTextColor;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_mode", length = 32)
    private GameMode gameMode;

    @Column(name = "include_in_ranking")
    private Boolean includeInRanking;

    @Column(name = "xp_enabled")
    private Boolean xpEnabled;

    @Column(name = "question_time_limit_seconds")
    private Integer questionTimeLimitSeconds;

    @Column(name = "questions_per_game")
    private Integer questionsPerGame;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private QuizStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "quiz_source", length = 16)
    private QuizSource source;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", length = 16)
    private QuizModerationStatus moderationStatus;

    @Column(name = "moderation_reason", length = 500)
    private String moderationReason;

    @Column(name = "moderation_updated_at")
    private Instant moderationUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private QuizCategory category;

    protected Quiz() {
    }

    public Quiz(String title, String description, QuizCategory category) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.gameMode = GameMode.CASUAL;
        this.includeInRanking = false;
        this.xpEnabled = true;
        this.questionTimeLimitSeconds = DEFAULT_QUESTION_TIME_LIMIT_SECONDS;
        this.questionsPerGame = DEFAULT_QUESTIONS_PER_GAME;
        this.status = QuizStatus.DRAFT;
        this.source = QuizSource.OFFICIAL;
        this.ownerUserId = null;
        this.moderationStatus = QuizModerationStatus.NONE;
        this.moderationReason = null;
        this.moderationUpdatedAt = null;
    }

    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version == null ? 0L : version;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getAvatarImageUrl() {
        return avatarImageUrl;
    }

    public String getAvatarBgStart() {
        return avatarBgStart;
    }

    public String getAvatarBgEnd() {
        return avatarBgEnd;
    }

    public String getAvatarTextColor() {
        return avatarTextColor;
    }

    public GameMode getGameMode() {
        return gameMode == null ? GameMode.CASUAL : gameMode;
    }

    public boolean isIncludeInRanking() {
        return Boolean.TRUE.equals(includeInRanking);
    }

    public boolean isXpEnabled() {
        return xpEnabled == null || xpEnabled;
    }

    public Integer getQuestionTimeLimitSeconds() {
        Integer v = questionTimeLimitSeconds;
        if (v == null || v <= 0) return DEFAULT_QUESTION_TIME_LIMIT_SECONDS;
        return v;
    }

    public Integer getQuestionsPerGame() {
        Integer v = questionsPerGame;
        if (v == null || v <= 0) return DEFAULT_QUESTIONS_PER_GAME;
        return v;
    }

    public QuizStatus getStatus() {
        return status == null ? QuizStatus.DRAFT : status;
    }

    public QuizSource getSource() {
        return source == null ? QuizSource.OFFICIAL : source;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public QuizModerationStatus getModerationStatus() {
        return moderationStatus == null ? QuizModerationStatus.NONE : moderationStatus;
    }

    public String getModerationReason() {
        return moderationReason;
    }

    public Instant getModerationUpdatedAt() {
        return moderationUpdatedAt;
    }

    public QuizCategory getCategory() {
        return category;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setAvatarImageUrl(String avatarImageUrl) {
        this.avatarImageUrl = avatarImageUrl;
    }

    public void setAvatarBgStart(String avatarBgStart) {
        this.avatarBgStart = avatarBgStart;
    }

    public void setAvatarBgEnd(String avatarBgEnd) {
        this.avatarBgEnd = avatarBgEnd;
    }

    public void setAvatarTextColor(String avatarTextColor) {
        this.avatarTextColor = avatarTextColor;
    }

    public void setGameMode(GameMode gameMode) {
        this.gameMode = gameMode;
    }

    public void setIncludeInRanking(Boolean includeInRanking) {
        this.includeInRanking = includeInRanking;
    }

    public void setXpEnabled(Boolean xpEnabled) {
        this.xpEnabled = xpEnabled;
    }

    public void setQuestionTimeLimitSeconds(Integer questionTimeLimitSeconds) {
        this.questionTimeLimitSeconds = questionTimeLimitSeconds;
    }

    public void setQuestionsPerGame(Integer questionsPerGame) {
        this.questionsPerGame = questionsPerGame;
    }

    public void setStatus(QuizStatus status) {
        this.status = status;
    }

    public void setSource(QuizSource source) {
        this.source = source;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public void setModerationStatus(QuizModerationStatus moderationStatus) {
        this.moderationStatus = moderationStatus;
    }

    public void setModerationReason(String moderationReason) {
        this.moderationReason = moderationReason;
    }

    public void setModerationUpdatedAt(Instant moderationUpdatedAt) {
        this.moderationUpdatedAt = moderationUpdatedAt;
    }

    public void setCategory(QuizCategory category) {
        this.category = category;
    }
}
