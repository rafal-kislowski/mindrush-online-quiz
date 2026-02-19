package pl.mindrush.backend.guest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface GuestSessionRepository extends JpaRepository<GuestSession, String> {

    @Query("""
            select s.id
            from GuestSession s
            where s.revoked = true
               or s.expiresAt <= :now
               or s.lastSeenAt < :cutoff
            """)
    List<String> findInactiveSessionIds(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}

