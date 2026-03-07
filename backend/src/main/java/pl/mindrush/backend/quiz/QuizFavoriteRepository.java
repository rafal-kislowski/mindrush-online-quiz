package pl.mindrush.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuizFavoriteRepository extends JpaRepository<QuizFavorite, Long> {
    List<QuizFavorite> findAllByUserId(Long userId);

    Optional<QuizFavorite> findByUserIdAndQuizId(Long userId, Long quizId);

    boolean existsByUserIdAndQuizId(Long userId, Long quizId);

    void deleteByUserIdAndQuizId(Long userId, Long quizId);

    void deleteAllByQuizId(Long quizId);
}
