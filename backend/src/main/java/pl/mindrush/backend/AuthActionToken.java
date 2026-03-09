package pl.mindrush.backend;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "auth_action_tokens",
        indexes = {
                @Index(name = "idx_auth_action_tokens_user_type", columnList = "user_id, token_type, consumed_at"),
                @Index(name = "idx_auth_action_tokens_expires", columnList = "expires_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_auth_action_token_hash", columnNames = {"token_hash"})
        }
)
public class AuthActionToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 32)
    private AuthActionTokenType tokenType;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected AuthActionToken() {
    }

    public AuthActionToken(
            Long userId,
            AuthActionTokenType tokenType,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt
    ) {
        this.userId = userId;
        this.tokenType = tokenType;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public AuthActionTokenType getTokenType() {
        return tokenType;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }
}

