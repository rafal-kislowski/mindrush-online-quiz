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
@Table(name = "quiz_questions")
public class QuizQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false, updatable = false)
    private Quiz quiz;

    @Column(name = "prompt", length = 500, nullable = false)
    private String prompt;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    protected QuizQuestion() {
    }

    public QuizQuestion(Quiz quiz, String prompt, int orderIndex) {
        this.quiz = quiz;
        this.prompt = prompt;
        this.orderIndex = orderIndex;
    }

    public Long getId() {
        return id;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
