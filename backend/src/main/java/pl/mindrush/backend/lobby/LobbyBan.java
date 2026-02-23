package pl.mindrush.backend.lobby;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "lobby_bans",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_lobby_ban_session", columnNames = {"lobby_id", "guest_session_id"}),
                @UniqueConstraint(name = "uq_lobby_ban_user", columnNames = {"lobby_id", "user_id"})
        }
)
public class LobbyBan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lobby_id", length = 36, nullable = false, updatable = false)
    private String lobbyId;

    @Column(name = "guest_session_id", length = 36, updatable = false)
    private String guestSessionId;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static LobbyBan create(String lobbyId, String guestSessionId, Long userId, Instant createdAt) {
        if ((guestSessionId == null || guestSessionId.isBlank()) && userId == null) {
            throw new IllegalArgumentException("At least one ban identity is required");
        }
        LobbyBan ban = new LobbyBan();
        ban.lobbyId = lobbyId;
        ban.guestSessionId = guestSessionId;
        ban.userId = userId;
        ban.createdAt = createdAt;
        return ban;
    }

    public Long getId() {
        return id;
    }

    public String getLobbyId() {
        return lobbyId;
    }

    public String getGuestSessionId() {
        return guestSessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
