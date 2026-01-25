package pl.mindrush.backend.lobby;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lobbies")
public class Lobby {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "code", length = 6, nullable = false, unique = true, updatable = false)
    private String code;

    @Column(name = "owner_guest_session_id", length = 36, nullable = false)
    private String ownerGuestSessionId;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "max_players", nullable = false)
    private int maxPlayers;

    @Column(name = "empty_since")
    private Instant emptySince;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private LobbyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Lobby createNew(String code, String ownerGuestSessionId, int maxPlayers, String passwordHash, Instant now) {
        Lobby lobby = new Lobby();
        lobby.id = UUID.randomUUID().toString();
        lobby.code = code;
        lobby.ownerGuestSessionId = ownerGuestSessionId;
        lobby.maxPlayers = maxPlayers;
        lobby.passwordHash = passwordHash;
        lobby.emptySince = null;
        lobby.status = LobbyStatus.OPEN;
        lobby.createdAt = now;
        return lobby;
    }

    public String getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getOwnerGuestSessionId() {
        return ownerGuestSessionId;
    }

    public void setOwnerGuestSessionId(String ownerGuestSessionId) {
        this.ownerGuestSessionId = ownerGuestSessionId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public Instant getEmptySince() {
        return emptySince;
    }

    public void setEmptySince(Instant emptySince) {
        this.emptySince = emptySince;
    }

    public LobbyStatus getStatus() {
        return status;
    }

    public void setStatus(LobbyStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }
}
