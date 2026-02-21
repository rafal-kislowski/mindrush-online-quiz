package pl.mindrush.backend.lobby;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "lobby_participants",
        uniqueConstraints = @UniqueConstraint(name = "uq_lobby_participant_guest", columnNames = {"lobby_id", "guest_session_id"})
)
public class LobbyParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lobby_id", nullable = false, updatable = false)
    private Lobby lobby;

    @Column(name = "guest_session_id", length = 36, nullable = false, updatable = false)
    private String guestSessionId;

    @Column(name = "display_name", length = 32, nullable = false)
    private String displayName;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "ready")
    private Boolean ready;

    public static LobbyParticipant createGuest(Lobby lobby, String guestSessionId, String displayName, Instant now) {
        LobbyParticipant p = new LobbyParticipant();
        p.lobby = lobby;
        p.guestSessionId = guestSessionId;
        p.displayName = displayName;
        p.joinedAt = now;
        p.ready = false;
        return p;
    }

    public Long getId() {
        return id;
    }

    public Lobby getLobby() {
        return lobby;
    }

    public String getGuestSessionId() {
        return guestSessionId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public boolean isReady() {
        return Boolean.TRUE.equals(ready);
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }
}
