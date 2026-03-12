package pl.mindrush.backend.achievement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "user_achievement_unlocks",
        indexes = {
                @Index(name = "idx_user_achievement_unlocks_user", columnList = "user_id, unlocked_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_user_achievement_unlock", columnNames = {"user_id", "achievement_key"})
        }
)
public class UserAchievementUnlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "achievement_key", nullable = false, length = 64)
    private String achievementKey;

    @Column(name = "unlocked_at", nullable = false)
    private Instant unlockedAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getAchievementKey() {
        return achievementKey;
    }

    public void setAchievementKey(String achievementKey) {
        this.achievementKey = achievementKey;
    }

    public Instant getUnlockedAt() {
        return unlockedAt;
    }

    public void setUnlockedAt(Instant unlockedAt) {
        this.unlockedAt = unlockedAt;
    }
}
