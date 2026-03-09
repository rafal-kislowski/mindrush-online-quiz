package pl.mindrush.backend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface AuthActionTokenRepository extends JpaRepository<AuthActionToken, Long> {

    Optional<AuthActionToken> findByTokenHashAndTokenTypeAndConsumedAtIsNull(
            String tokenHash,
            AuthActionTokenType tokenType
    );

    void deleteByUserIdAndTokenTypeAndConsumedAtIsNull(Long userId, AuthActionTokenType tokenType);

    long deleteByExpiresAtBefore(Instant threshold);
}

