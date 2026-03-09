package pl.mindrush.backend;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
        name = "app_users",
        uniqueConstraints = @UniqueConstraint(name = "uq_app_user_email", columnNames = "email")
)
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", length = 190, nullable = false)
    private String email;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Column(name = "display_name", length = 32)
    private String displayName;

    @Column(name = "rank_points", nullable = false, columnDefinition = "integer not null default 0")
    private int rankPoints;

    @Column(name = "xp", nullable = false, columnDefinition = "integer not null default 0")
    private int xp;

    @Column(name = "coins", nullable = false, columnDefinition = "integer not null default 0")
    private int coins;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 32, nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<AppRole> roles = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "email_verified", nullable = false, columnDefinition = "boolean not null default true")
    private boolean emailVerified;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_verification_email_sent_at")
    private Instant lastVerificationEmailSentAt;

    @Column(name = "last_password_reset_email_sent_at")
    private Instant lastPasswordResetEmailSentAt;

    protected AppUser() {
    }

    public AppUser(String email, String passwordHash, String displayName, Set<AppRole> roles, Instant createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.rankPoints = 0;
        this.xp = 0;
        this.coins = 0;
        this.roles = roles == null ? new HashSet<>() : new HashSet<>(roles);
        this.createdAt = createdAt;
        this.emailVerified = true;
        this.emailVerifiedAt = createdAt;
        this.lastVerificationEmailSentAt = null;
        this.lastPasswordResetEmailSentAt = null;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getRankPoints() {
        return rankPoints;
    }

    public int getXp() {
        return xp;
    }

    public int getCoins() {
        return coins;
    }

    public Set<AppRole> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant getLastVerificationEmailSentAt() {
        return lastVerificationEmailSentAt;
    }

    public Instant getLastPasswordResetEmailSentAt() {
        return lastPasswordResetEmailSentAt;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setRankPoints(int rankPoints) {
        this.rankPoints = Math.max(0, rankPoints);
    }

    public void setXp(int xp) {
        this.xp = Math.max(0, xp);
    }

    public void setCoins(int coins) {
        this.coins = Math.max(0, coins);
    }

    public void setRoles(Set<AppRole> roles) {
        this.roles = roles == null ? new HashSet<>() : new HashSet<>(roles);
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public void setLastVerificationEmailSentAt(Instant lastVerificationEmailSentAt) {
        this.lastVerificationEmailSentAt = lastVerificationEmailSentAt;
    }

    public void setLastPasswordResetEmailSentAt(Instant lastPasswordResetEmailSentAt) {
        this.lastPasswordResetEmailSentAt = lastPasswordResetEmailSentAt;
    }
}
