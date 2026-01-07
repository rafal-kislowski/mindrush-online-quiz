package pl.mindrush.backend.game;

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

@Entity
@Table(
        name = "game_players",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_game_player_once",
                columnNames = {"game_session_id", "guest_session_id"}
        )
)
public class GamePlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_session_id", nullable = false, updatable = false)
    private GameSession gameSession;

    @Column(name = "guest_session_id", length = 36, nullable = false, updatable = false)
    private String guestSessionId;

    @Column(name = "display_name", length = 32, nullable = false, updatable = false)
    private String displayName;

    @Column(name = "order_index", nullable = false, updatable = false)
    private int orderIndex;

    protected GamePlayer() {
    }

    public static GamePlayer create(GameSession session, String guestSessionId, String displayName, int orderIndex) {
        GamePlayer p = new GamePlayer();
        p.gameSession = session;
        p.guestSessionId = guestSessionId;
        p.displayName = displayName;
        p.orderIndex = orderIndex;
        return p;
    }

    public Long getId() {
        return id;
    }

    public GameSession getGameSession() {
        return gameSession;
    }

    public String getGuestSessionId() {
        return guestSessionId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getOrderIndex() {
        return orderIndex;
    }
}

