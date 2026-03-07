package pl.mindrush.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuizModerationIssueRepository extends JpaRepository<QuizModerationIssue, Long> {

    List<QuizModerationIssue> findAllByQuizIdOrderByIdAsc(Long quizId);

    void deleteAllByQuizId(Long quizId);

    long countByQuizId(Long quizId);
}
