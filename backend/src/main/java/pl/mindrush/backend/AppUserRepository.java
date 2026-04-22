package pl.mindrush.backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByDisplayNameIgnoreCase(String displayName);
    boolean existsByDisplayNameIgnoreCaseAndIdNot(String displayName, Long id);

    List<AppUser> findAllByOrderByRankPointsDescIdAsc(Pageable pageable);

    @Query("""
            select lower(u.displayName), count(u.id)
            from AppUser u
            where u.displayName is not null
              and lower(u.displayName) in :namesLower
            group by lower(u.displayName)
            """)
    List<Object[]> countByDisplayNameLowerIn(@Param("namesLower") List<String> namesLower);

    @Query("""
            select count(u.id)
            from AppUser u
            where u.rankPoints > :rankPoints
               or (u.rankPoints = :rankPoints and u.id < :userId)
            """)
    long countAhead(@Param("rankPoints") int rankPoints, @Param("userId") long userId);

    @Query("""
            select u.id
            from AppUser u
            join u.roles role
            where role = :role
            """)
    List<Long> findAllIdsByRole(@Param("role") AppRole role);

    @Query("""
            select u.id
            from AppUser u
            join u.roles role
            where role = :role
              and u.premiumExpiresAt is not null
              and u.premiumExpiresAt <= :cutoff
            """)
    List<Long> findAllIdsByRoleAndPremiumExpiresAtLessThanEqual(
            @Param("role") AppRole role,
            @Param("cutoff") Instant cutoff
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);
}
