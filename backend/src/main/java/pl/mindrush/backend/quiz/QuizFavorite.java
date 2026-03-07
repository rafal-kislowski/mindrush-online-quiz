package pl.mindrush.backend.quiz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "quiz_favorites",
        uniqueConstraints = @UniqueConstraint(name = "uq_quiz_favorite_user_quiz", columnNames = {"user_id", "quiz_id"})
)
public class QuizFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "quiz_id", nullable = false)
    private Long quizId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected QuizFavorite() {
    }

    public static QuizFavorite create(Long userId, Long quizId, Instant now) {
        QuizFavorite favorite = new QuizFavorite();
        favorite.userId = userId;
        favorite.quizId = quizId;
        favorite.createdAt = now;
        return favorite;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getQuizId() {
        return quizId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

