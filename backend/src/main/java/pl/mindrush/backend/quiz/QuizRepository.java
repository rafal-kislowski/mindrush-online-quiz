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

    @Query("""
            select q
            from Quiz q
            left join fetch q.category
            where q.ownerUserId = :ownerUserId
            order by q.id desc
            """)
    List<Quiz> findAllOwnedByUserId(Long ownerUserId);

    @Query("""
            select q
            from Quiz q
            left join fetch q.category
            where q.ownerUserId = :ownerUserId
              and q.status <> pl.mindrush.backend.quiz.QuizStatus.TRASHED
            order by q.id desc
            """)
    List<Quiz> findAllOwnedVisibleByUserId(Long ownerUserId);

    @Query("""
            select q
            from Quiz q
            left join fetch q.category
            where q.id in :ids
              and q.status = pl.mindrush.backend.quiz.QuizStatus.ACTIVE
            """)
    List<Quiz> findAllWithCategoryByIdInAndStatusActive(List<Long> ids);

    @Query("""
            select q
            from Quiz q
            left join fetch q.category
            where q.ownerUserId = :ownerUserId
              and q.id = :quizId
            """)
    Optional<Quiz> findOwnedByUserIdAndId(Long ownerUserId, Long quizId);

    @Query("""
            select q
            from Quiz q
            left join fetch q.category
            where q.moderationStatus = :status
            order by q.id asc
            """)
    List<Quiz> findAllWithCategoryByModerationStatus(QuizModerationStatus status);

    long countByOwnerUserIdAndStatusNot(Long ownerUserId, QuizStatus status);

    long countByOwnerUserIdAndModerationStatus(Long ownerUserId, QuizModerationStatus moderationStatus);

    long countByOwnerUserIdAndStatusAndModerationStatus(
            Long ownerUserId,
            QuizStatus status,
            QuizModerationStatus moderationStatus
    );
}
