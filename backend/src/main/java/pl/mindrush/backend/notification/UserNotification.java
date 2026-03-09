package pl.mindrush.backend.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "user_notifications",
        indexes = {
                @Index(name = "idx_user_notifications_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_user_notifications_user_unread", columnList = "user_id, read_at, dismissed_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_user_notification_dedupe", columnNames = {"user_id", "dedupe_key"})
        }
)
public class UserNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private UserNotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private UserNotificationSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private UserNotificationDecision decision;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(length = 220)
    private String subtitle;

    @Column(length = 500)
    private String text;

    @Column(length = 200)
    private String meta;

    @Column(name = "avatar_image_url", length = 500)
    private String avatarImageUrl;

    @Column(name = "avatar_bg_start", length = 32)
    private String avatarBgStart;

    @Column(name = "avatar_bg_end", length = 32)
    private String avatarBgEnd;

    @Column(name = "avatar_text_color", length = 32)
    private String avatarTextColor;

    @Column(name = "route_path", length = 255)
    private String routePath;

    @Lob
    @Column(name = "route_query_json")
    private String routeQueryJson;

    @Lob
    @Column(name = "payload_json")
    private String payloadJson;

    @Column(name = "dedupe_key", length = 190)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public UserNotificationCategory getCategory() {
        return category;
    }

    public void setCategory(UserNotificationCategory category) {
        this.category = category;
    }

    public UserNotificationSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(UserNotificationSeverity severity) {
        this.severity = severity;
    }

    public UserNotificationDecision getDecision() {
        return decision;
    }

    public void setDecision(UserNotificationDecision decision) {
        this.decision = decision;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getMeta() {
        return meta;
    }

    public void setMeta(String meta) {
        this.meta = meta;
    }

    public String getAvatarImageUrl() {
        return avatarImageUrl;
    }

    public void setAvatarImageUrl(String avatarImageUrl) {
        this.avatarImageUrl = avatarImageUrl;
    }

    public String getAvatarBgStart() {
        return avatarBgStart;
    }

    public void setAvatarBgStart(String avatarBgStart) {
        this.avatarBgStart = avatarBgStart;
    }

    public String getAvatarBgEnd() {
        return avatarBgEnd;
    }

    public void setAvatarBgEnd(String avatarBgEnd) {
        this.avatarBgEnd = avatarBgEnd;
    }

    public String getAvatarTextColor() {
        return avatarTextColor;
    }

    public void setAvatarTextColor(String avatarTextColor) {
        this.avatarTextColor = avatarTextColor;
    }

    public String getRoutePath() {
        return routePath;
    }

    public void setRoutePath(String routePath) {
        this.routePath = routePath;
    }

    public String getRouteQueryJson() {
        return routeQueryJson;
    }

    public void setRouteQueryJson(String routeQueryJson) {
        this.routeQueryJson = routeQueryJson;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getDismissedAt() {
        return dismissedAt;
    }

    public void setDismissedAt(Instant dismissedAt) {
        this.dismissedAt = dismissedAt;
    }
}

