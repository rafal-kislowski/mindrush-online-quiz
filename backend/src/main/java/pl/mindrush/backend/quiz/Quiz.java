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

@Entity
@Table(name = "quizzes")
public class Quiz {

    public static final int DEFAULT_QUESTION_TIME_LIMIT_SECONDS = 15;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private QuizStatus status;

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
        this.status = QuizStatus.DRAFT;
    }

    public Long getId() {
        return id;
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

    public QuizStatus getStatus() {
        return status == null ? QuizStatus.DRAFT : status;
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

    public void setStatus(QuizStatus status) {
        this.status = status;
    }

    public void setCategory(QuizCategory category) {
        this.category = category;
    }
}
