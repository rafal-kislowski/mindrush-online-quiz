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

@Entity
@Table(name = "quizzes")
public class Quiz {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private QuizCategory category;

    protected Quiz() {
    }

    public Quiz(String title, String description, QuizCategory category) {
        this.title = title;
        this.description = description;
        this.category = category;
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

    public void setCategory(QuizCategory category) {
        this.category = category;
    }
}
