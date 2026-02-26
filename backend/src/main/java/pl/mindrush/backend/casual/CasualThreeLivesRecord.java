package pl.mindrush.backend.casual;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "casual_three_lives_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_casual_three_lives_participant", columnNames = "participant_key")
)
public class CasualThreeLivesRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_key", length = 128, nullable = false)
    private String participantKey;

    @Column(name = "guest_session_id", length = 36, nullable = false)
    private String guestSessionId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "best_points", nullable = false, columnDefinition = "integer not null default 0")
    private int bestPoints;

    @Column(name = "best_answered", nullable = false, columnDefinition = "integer not null default 0")
    private int bestAnswered;

    @Column(name = "best_duration_ms", nullable = false, columnDefinition = "bigint not null default 0")
    private long bestDurationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CasualThreeLivesRecord() {
    }

    public static CasualThreeLivesRecord create(
            String participantKey,
            String guestSessionId,
            Long userId,
            int bestPoints,
            int bestAnswered,
            long bestDurationMs,
            Instant now
    ) {
        CasualThreeLivesRecord record = new CasualThreeLivesRecord();
        record.participantKey = participantKey;
        record.guestSessionId = guestSessionId;
        record.userId = userId;
        record.bestPoints = Math.max(0, bestPoints);
        record.bestAnswered = Math.max(0, bestAnswered);
        record.bestDurationMs = Math.max(0L, bestDurationMs);
        record.createdAt = now;
        record.updatedAt = now;
        return record;
    }

    public Long getId() {
        return id;
    }

    public String getParticipantKey() {
        return participantKey;
    }

    public String getGuestSessionId() {
        return guestSessionId;
    }

    public void setGuestSessionId(String guestSessionId) {
        this.guestSessionId = guestSessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public int getBestPoints() {
        return bestPoints;
    }

    public void setBestPoints(int bestPoints) {
        this.bestPoints = Math.max(0, bestPoints);
    }

    public int getBestAnswered() {
        return bestAnswered;
    }

    public void setBestAnswered(int bestAnswered) {
        this.bestAnswered = Math.max(0, bestAnswered);
    }

    public long getBestDurationMs() {
        return bestDurationMs;
    }

    public void setBestDurationMs(long bestDurationMs) {
        this.bestDurationMs = Math.max(0L, bestDurationMs);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

