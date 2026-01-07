package pl.mindrush.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QuizCategoryRepository extends JpaRepository<QuizCategory, Long> {
    Optional<QuizCategory> findByName(String name);
}

