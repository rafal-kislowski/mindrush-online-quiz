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
@Table(name = "quiz_moderation_issues")
public class QuizModerationIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false, updatable = false)
    private Quiz quiz;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    protected QuizModerationIssue() {
    }

    public QuizModerationIssue(Quiz quiz, Long questionId, String message) {
        this.quiz = quiz;
        this.questionId = questionId;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getMessage() {
        return message;
    }
}
