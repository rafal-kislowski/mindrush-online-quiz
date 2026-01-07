package pl.mindrush.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, Long> {

    List<QuizQuestion> findAllByQuizIdOrderByOrderIndexAsc(Long quizId);

    @Query("select count(q.id) from QuizQuestion q where q.quiz.id = :quizId")
    long countByQuizId(@Param("quizId") Long quizId);
}

