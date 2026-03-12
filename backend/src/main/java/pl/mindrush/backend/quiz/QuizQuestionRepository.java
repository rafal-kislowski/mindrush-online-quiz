package pl.mindrush.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, Long> {

    List<QuizQuestion> findAllByQuizIdOrderByOrderIndexAsc(Long quizId);
    Optional<QuizQuestion> findByIdAndQuizId(Long id, Long quizId);

    @Query("""
            select qq.id
            from QuizQuestion qq
            where qq.quiz.id = :quizId
            order by qq.orderIndex asc
            """)
    List<Long> findIdsByQuizIdOrderByOrderIndexAsc(@Param("quizId") Long quizId);

    @Query("""
            select qq
            from QuizQuestion qq
            join qq.quiz q
            where q.category.id = :categoryId and q.status = :status
            order by q.id asc, qq.orderIndex asc
            """)
    List<QuizQuestion> findAllByQuizCategoryIdAndQuizStatusOrderByQuizIdAscOrderIndexAsc(
            @Param("categoryId") Long categoryId,
            @Param("status") QuizStatus status
    );

    @Query("select count(q.id) from QuizQuestion q where q.quiz.id = :quizId")
    long countByQuizId(@Param("quizId") Long quizId);

    @Query("select count(q.id) from QuizQuestion q where q.quiz.id = :quizId and q.imageUrl is not null")
    long countByQuizIdWithImage(@Param("quizId") Long quizId);

    @Query("""
            select count(qq.id)
            from QuizQuestion qq
            join qq.quiz q
            where q.category.id = :categoryId and q.status = :status
            """)
    long countByQuizCategoryIdAndQuizStatus(
            @Param("categoryId") Long categoryId,
            @Param("status") QuizStatus status
    );
}
