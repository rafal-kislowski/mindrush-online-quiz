package pl.mindrush.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

    @Query("select q from Quiz q left join fetch q.category")
    List<Quiz> findAllWithCategory();

    @Query("select q from Quiz q left join fetch q.category where q.id = :id")
    Optional<Quiz> findByIdWithCategory(Long id);

    @Query("select q from Quiz q left join fetch q.category where q.status = :status")
    List<Quiz> findAllWithCategoryByStatus(QuizStatus status);
}
