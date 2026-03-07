package pl.mindrush.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QuizAnswerOptionRepository extends JpaRepository<QuizAnswerOption, Long> {
    List<QuizAnswerOption> findAllByQuestionIdOrderByOrderIndexAsc(Long questionId);
    List<QuizAnswerOption> findAllByQuestionIdInOrderByQuestionIdAscOrderIndexAsc(Collection<Long> questionIds);
    Optional<QuizAnswerOption> findByIdAndQuestionId(Long id, Long questionId);
    void deleteAllByQuestionId(Long questionId);
    void deleteAllByQuestionIdIn(Collection<Long> questionIds);
}
