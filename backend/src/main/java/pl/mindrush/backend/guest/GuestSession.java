package pl.mindrush.backend.guest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "guest_sessions")
public class GuestSession {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "display_name", length = 32)
    private String displayName;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "rank_points", nullable = false, columnDefinition = "integer not null default 0")
    private int rankPoints;

    @Column(name = "xp", nullable = false, columnDefinition = "integer not null default 0")
    private int xp;

    @Column(name = "coins", nullable = false, columnDefinition = "integer not null default 0")
    private int coins;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public int getRankPoints() {
        return rankPoints;
    }

    public void setRankPoints(int rankPoints) {
        this.rankPoints = Math.max(0, rankPoints);
    }

    public int getXp() {
        return xp;
    }

    public void setXp(int xp) {
        this.xp = Math.max(0, xp);
    }

    public int getCoins() {
        return coins;
    }

    public void setCoins(int coins) {
        this.coins = Math.max(0, coins);
    }

    public static GuestSession createNew(Instant now, Instant expiresAt) {
        GuestSession session = new GuestSession();
        session.id = UUID.randomUUID().toString();
        session.createdAt = now;
        session.lastSeenAt = now;
        session.expiresAt = expiresAt;
        session.userId = null;
        session.rankPoints = 0;
        session.xp = 0;
        session.coins = 0;
        session.revoked = false;
        return session;
    }
}
