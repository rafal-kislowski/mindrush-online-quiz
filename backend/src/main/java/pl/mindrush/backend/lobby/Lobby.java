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

    @Column(name = "owner_authenticated", nullable = false)
    private boolean ownerAuthenticated;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "pin_code", length = 8)
    private String pinCode;

    @Column(name = "max_players", nullable = false)
    private int maxPlayers;

    @Column(name = "empty_since")
    private Instant emptySince;

    @Column(name = "selected_quiz_id")
    private Long selectedQuizId;

    @Column(name = "ranking_enabled", nullable = false)
    private boolean rankingEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private LobbyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Lobby createNew(
            String code,
            String ownerGuestSessionId,
            boolean ownerAuthenticated,
            int maxPlayers,
            String passwordHash,
            String pinCode,
            Instant now
    ) {
        Lobby lobby = new Lobby();
        lobby.id = UUID.randomUUID().toString();
        lobby.code = code;
        lobby.ownerGuestSessionId = ownerGuestSessionId;
        lobby.ownerAuthenticated = ownerAuthenticated;
        lobby.maxPlayers = maxPlayers;
        lobby.passwordHash = passwordHash;
        lobby.pinCode = pinCode;
        lobby.emptySince = null;
        lobby.rankingEnabled = false;
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

    public boolean isOwnerAuthenticated() {
        return ownerAuthenticated;
    }

    public void setOwnerAuthenticated(boolean ownerAuthenticated) {
        this.ownerAuthenticated = ownerAuthenticated;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPinCode() {
        return pinCode;
    }

    public void setPinCode(String pinCode) {
        this.pinCode = pinCode;
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

    public Long getSelectedQuizId() {
        return selectedQuizId;
    }

    public void setSelectedQuizId(Long selectedQuizId) {
        this.selectedQuizId = selectedQuizId;
    }

    public boolean isRankingEnabled() {
        return rankingEnabled;
    }

    public void setRankingEnabled(boolean rankingEnabled) {
        this.rankingEnabled = rankingEnabled;
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
