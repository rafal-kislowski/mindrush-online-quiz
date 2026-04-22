package pl.mindrush.backend.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.quiz.Quiz;
import pl.mindrush.backend.quiz.QuizModerationStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class UserNotificationService {

    private static final int MAX_LIMIT = 100;
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};
    private static final String PREMIUM_NOTIFICATION_AVATAR = "/shop/Premium_account_icon.png";

    private final UserNotificationRepository notificationRepository;
    private final UserNotificationStreamService streamService;
    private final AppUserRepository appUserRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public UserNotificationService(
            UserNotificationRepository notificationRepository,
            UserNotificationStreamService streamService,
            AppUserRepository appUserRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.notificationRepository = notificationRepository;
        this.streamService = streamService;
        this.appUserRepository = appUserRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationListResponse list(Long userId, int limit) {
        int safeLimit = sanitizeLimit(limit);
        List<UserNotificationListItem> items = notificationRepository
                .findByUserIdAndDismissedAtIsNullOrderByCreatedAtDesc(userId, PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toListItem)
                .toList();
        long unreadCount = notificationRepository.countByUserIdAndReadAtIsNullAndDismissedAtIsNull(userId);
        return new NotificationListResponse(items, unreadCount);
    }

    public UserNotificationListItem markRead(Long userId, Long notificationId) {
        UserNotification notification = findOwnedNotification(userId, notificationId);
        if (notification.getDismissedAt() != null) {
            throw new ResponseStatusException(NOT_FOUND, "Notification not found");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(clock.instant());
            notification = notificationRepository.save(notification);
            publishRefresh(userId);
        }
        return toListItem(notification);
    }

    public void dismiss(Long userId, Long notificationId) {
        UserNotification notification = findOwnedNotification(userId, notificationId);
        if (notification.getDismissedAt() != null) return;

        Instant now = clock.instant();
        if (notification.getReadAt() == null) {
            notification.setReadAt(now);
        }
        notification.setDismissedAt(now);
        notificationRepository.save(notification);
        publishRefresh(userId);
    }

    public long markAllRead(Long userId) {
        Instant now = clock.instant();
        List<UserNotification> unread = notificationRepository.findByUserIdAndReadAtIsNullAndDismissedAtIsNull(userId);
        if (unread.isEmpty()) return 0L;

        for (UserNotification notification : unread) {
            notification.setReadAt(now);
        }
        notificationRepository.saveAll(unread);
        publishRefresh(userId);
        return unread.size();
    }

    public void createModerationDecisionNotification(Quiz quiz, int questionIssueCount) {
        if (quiz == null || quiz.getOwnerUserId() == null) return;

        QuizModerationStatus status = quiz.getModerationStatus();
        if (status != QuizModerationStatus.APPROVED && status != QuizModerationStatus.REJECTED) return;

        Long userId = quiz.getOwnerUserId();
        Instant createdAt = quiz.getModerationUpdatedAt() != null ? quiz.getModerationUpdatedAt() : clock.instant();
        String dedupeKey = "moderation:%d:%s:%d".formatted(
                quiz.getId(),
                status.name().toLowerCase(Locale.ROOT),
                createdAt.toEpochMilli()
        );
        if (notificationRepository.existsByUserIdAndDedupeKey(userId, dedupeKey)) {
            return;
        }

        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setCategory(UserNotificationCategory.MODERATION);
        notification.setSeverity(status == QuizModerationStatus.APPROVED
                ? UserNotificationSeverity.SUCCESS
                : UserNotificationSeverity.DANGER);
        notification.setDecision(status == QuizModerationStatus.APPROVED
                ? UserNotificationDecision.APPROVED
                : UserNotificationDecision.REJECTED);
        notification.setTitle("Quiz verification");
        notification.setSubtitle(quiz.getTitle());
        notification.setText(status == QuizModerationStatus.APPROVED
                ? "Your quiz has passed moderation and is now published."
                : "Your quiz was rejected during moderation.");
        notification.setMeta(buildModerationMeta(status, questionIssueCount));
        notification.setAvatarImageUrl(quiz.getAvatarImageUrl());
        notification.setAvatarBgStart(quiz.getAvatarBgStart());
        notification.setAvatarBgEnd(quiz.getAvatarBgEnd());
        notification.setAvatarTextColor(quiz.getAvatarTextColor());
        notification.setRoutePath("/library");
        notification.setRouteQueryJson(toJsonString(buildModerationRouteQuery(quiz, status, questionIssueCount)));
        notification.setPayloadJson(toJsonString(Map.of(
                "quizId", quiz.getId(),
                "status", status.name(),
                "questionIssueCount", Math.max(0, questionIssueCount)
        )));
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(createdAt);
        notificationRepository.save(notification);

        publishRefresh(userId);
    }

    public void createModerationSubmissionNotificationForAdmins(Quiz quiz, String submitterDisplayName) {
        if (quiz == null || quiz.getId() == null) return;
        if (quiz.getModerationStatus() != QuizModerationStatus.PENDING) return;

        List<Long> adminIds = appUserRepository.findAllIdsByRole(AppRole.ADMIN).stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (adminIds.isEmpty()) return;

        Instant createdAt = quiz.getModerationUpdatedAt() != null ? quiz.getModerationUpdatedAt() : clock.instant();
        String dedupeKey = "moderation:submitted:%d:%d".formatted(quiz.getId(), quiz.getVersion() == null ? 0L : quiz.getVersion());

        String quizTitle = String.valueOf(quiz.getTitle() == null ? "" : quiz.getTitle()).trim();
        if (quizTitle.isBlank()) {
            quizTitle = "Untitled quiz";
        }
        String authorLabel = String.valueOf(submitterDisplayName == null ? "" : submitterDisplayName).trim();
        if (authorLabel.isBlank()) {
            authorLabel = "A user";
        }

        String text = "%s submitted a quiz for moderation review.".formatted(authorLabel);
        for (Long adminId : adminIds) {
            if (notificationRepository.existsByUserIdAndDedupeKey(adminId, dedupeKey)) {
                continue;
            }

            UserNotification notification = new UserNotification();
            notification.setUserId(adminId);
            notification.setCategory(UserNotificationCategory.MODERATION);
            notification.setSeverity(UserNotificationSeverity.WARNING);
            notification.setDecision(null);
            notification.setTitle("New quiz for review");
            notification.setSubtitle(quizTitle);
            notification.setText(text);
            notification.setMeta("Pending moderation");
            notification.setAvatarImageUrl(quiz.getAvatarImageUrl());
            notification.setAvatarBgStart(quiz.getAvatarBgStart());
            notification.setAvatarBgEnd(quiz.getAvatarBgEnd());
            notification.setAvatarTextColor(quiz.getAvatarTextColor());
            notification.setRoutePath("/admin/quiz-submissions");
            notification.setRouteQueryJson(toJsonString(Map.of(
                    "openQuiz", quiz.getId()
            )));
            notification.setPayloadJson(toJsonString(Map.of(
                    "quizId", quiz.getId(),
                    "status", QuizModerationStatus.PENDING.name(),
                    "ownerUserId", quiz.getOwnerUserId() == null ? 0L : quiz.getOwnerUserId()
            )));
            notification.setDedupeKey(dedupeKey);
            notification.setCreatedAt(createdAt);
            notificationRepository.save(notification);

            publishRefresh(adminId);
        }
    }

    public void createAchievementUnlockedNotification(
            Long userId,
            String achievementKey,
            String achievementTitle,
            String achievementDescription,
            String iconClass,
            Instant createdAt
    ) {
        if (userId == null) return;

        String normalizedKey = String.valueOf(achievementKey == null ? "" : achievementKey).trim();
        String normalizedTitle = String.valueOf(achievementTitle == null ? "" : achievementTitle).trim();
        if (normalizedKey.isBlank() || normalizedTitle.isBlank()) return;

        Instant now = createdAt != null ? createdAt : clock.instant();
        String dedupeKey = "achievement:%s".formatted(normalizedKey.toLowerCase(Locale.ROOT));
        if (notificationRepository.existsByUserIdAndDedupeKey(userId, dedupeKey)) {
            return;
        }

        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setCategory(UserNotificationCategory.GIFT);
        notification.setSeverity(UserNotificationSeverity.SUCCESS);
        notification.setDecision(null);
        notification.setTitle("Achievement unlocked");
        notification.setSubtitle(normalizedTitle);
        notification.setText(String.valueOf(achievementDescription == null ? "" : achievementDescription).trim());
        notification.setMeta("Milestone completed");
        notification.setAvatarImageUrl(null);
        notification.setAvatarBgStart("#F6C453");
        notification.setAvatarBgEnd("#F08A3E");
        notification.setAvatarTextColor("#1B1100");
        notification.setRoutePath("/profile");
        notification.setRouteQueryJson(toJsonString(Map.of(
                "tab", "stats",
                "section", "achievements"
        )));
        notification.setPayloadJson(toJsonString(Map.of(
                "type", "achievement",
                "achievementKey", normalizedKey,
                "icon", String.valueOf(iconClass == null ? "" : iconClass).trim()
        )));
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(now);
        notificationRepository.save(notification);

        publishRefresh(userId);
    }

    public void createPremiumActivatedNotification(Long userId, Instant premiumEndsAt) {
        createPremiumActivatedNotification(userId, premiumEndsAt, false);
    }

    public void createPremiumActivatedNotification(Long userId, Instant premiumEndsAt, boolean extended) {
        if (userId == null) return;
        String eventType = extended ? "extended" : "activated";
        String dedupeKey = "premium:%s:%s".formatted(eventType, premiumEndsAt == null ? "unknown" : premiumEndsAt.toEpochMilli());
        if (notificationRepository.existsByUserIdAndDedupeKey(userId, dedupeKey)) {
            return;
        }

        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setCategory(UserNotificationCategory.SYSTEM);
        notification.setSeverity(UserNotificationSeverity.SUCCESS);
        notification.setDecision(null);
        notification.setTitle(extended ? "Premium extended" : "Premium activated");
        notification.setSubtitle("MindRush Premium");
        notification.setText(extended
                ? "Your premium time was successfully extended."
                : "Your premium account is active and all premium limits are now enabled.");
        notification.setMeta(premiumEndsAt == null
                ? (extended ? "Extended now" : "Active now")
                : (extended ? "Extended until " + premiumEndsAt : "Active until " + premiumEndsAt));
        notification.setAvatarImageUrl(PREMIUM_NOTIFICATION_AVATAR);
        notification.setAvatarBgStart("#F6C453");
        notification.setAvatarBgEnd("#2F8DFF");
        notification.setAvatarTextColor("#09111F");
        notification.setRoutePath("/shop/premium");
        notification.setRouteQueryJson(null);
        notification.setPayloadJson(toJsonString(Map.of(
                "type", extended ? "premium_extended" : "premium_activated",
                "premiumEndsAt", premiumEndsAt == null ? "" : premiumEndsAt.toString()
        )));
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(clock.instant());
        notificationRepository.save(notification);

        publishRefresh(userId);
    }

    public void createPremiumExpiredNotification(Long userId, Instant premiumExpiredAt) {
        if (userId == null) return;
        String dedupeKey = "premium:expired:%s".formatted(premiumExpiredAt == null ? "unknown" : premiumExpiredAt.toEpochMilli());
        if (notificationRepository.existsByUserIdAndDedupeKey(userId, dedupeKey)) {
            return;
        }

        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setCategory(UserNotificationCategory.SYSTEM);
        notification.setSeverity(UserNotificationSeverity.WARNING);
        notification.setDecision(null);
        notification.setTitle("Premium expired");
        notification.setSubtitle("MindRush Premium");
        notification.setText("Your premium time has ended. Existing content stays intact, but premium-only limits are no longer available.");
        notification.setMeta(premiumExpiredAt == null ? "Expired" : "Expired at " + premiumExpiredAt);
        notification.setAvatarImageUrl(PREMIUM_NOTIFICATION_AVATAR);
        notification.setAvatarBgStart("#F6C453");
        notification.setAvatarBgEnd("#23304B");
        notification.setAvatarTextColor("#FFFFFF");
        notification.setRoutePath("/shop/premium");
        notification.setRouteQueryJson(null);
        notification.setPayloadJson(toJsonString(Map.of(
                "type", "premium_expired",
                "premiumExpiredAt", premiumExpiredAt == null ? "" : premiumExpiredAt.toString()
        )));
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(clock.instant());
        notificationRepository.save(notification);

        publishRefresh(userId);
    }

    public void publishRefresh(Long userId) {
        long unreadCount = notificationRepository.countByUserIdAndReadAtIsNullAndDismissedAtIsNull(userId);
        streamService.publishRefresh(userId, unreadCount);
    }

    private static String buildModerationMeta(QuizModerationStatus status, int questionIssueCount) {
        if (status == QuizModerationStatus.APPROVED) return "Approved";
        int issues = Math.max(0, questionIssueCount);
        if (issues <= 0) return "Rejected";
        return "Rejected - %d question issue%s".formatted(issues, issues == 1 ? "" : "s");
    }

    private static Map<String, Object> buildModerationRouteQuery(
            Quiz quiz,
            QuizModerationStatus status,
            int questionIssueCount
    ) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("openQuiz", quiz.getId());
        query.put("moderationTab", questionIssueCount > 0 ? "questions" : "details");
        if (status == QuizModerationStatus.REJECTED) {
            query.put("reopenModeration", "1");
        }
        return query;
    }

    private int sanitizeLimit(int limit) {
        if (limit <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "limit must be greater than 0");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private UserNotification findOwnedNotification(Long userId, Long notificationId) {
        if (notificationId == null || notificationId <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid notification id");
        }
        return notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Notification not found"));
    }

    private String toJsonString(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return null;
        }
    }

    private UserNotificationListItem toListItem(UserNotification notification) {
        return new UserNotificationListItem(
                notification.getId(),
                toCategoryLabel(notification.getCategory()),
                toSeverityLabel(notification.getSeverity()),
                notification.getTitle(),
                notification.getSubtitle(),
                notification.getText(),
                notification.getMeta(),
                notification.getCreatedAt(),
                notification.getReadAt(),
                toDecisionLabel(notification.getDecision()),
                notification.getAvatarImageUrl(),
                notification.getAvatarBgStart(),
                notification.getAvatarBgEnd(),
                notification.getAvatarTextColor(),
                notification.getRoutePath(),
                parseRouteQuery(notification.getRouteQueryJson())
        );
    }

    private Map<String, Object> parseRouteQuery(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_REF);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String toCategoryLabel(UserNotificationCategory category) {
        if (category == null) return "system";
        if (category == UserNotificationCategory.MODERATION) return "moderation";
        if (category == UserNotificationCategory.GIFT) return "reward";
        if (category == UserNotificationCategory.NEWS) return "news";
        return "system";
    }

    private static String toSeverityLabel(UserNotificationSeverity severity) {
        if (severity == null) return "neutral";
        if (severity == UserNotificationSeverity.SUCCESS) return "success";
        if (severity == UserNotificationSeverity.WARNING) return "warning";
        if (severity == UserNotificationSeverity.DANGER) return "danger";
        return "neutral";
    }

    private static String toDecisionLabel(UserNotificationDecision decision) {
        if (decision == null) return null;
        if (decision == UserNotificationDecision.APPROVED) return "approved";
        return "rejected";
    }

    public record UserNotificationListItem(
            Long id,
            String category,
            String severity,
            String title,
            String subtitle,
            String text,
            String meta,
            Instant createdAt,
            Instant readAt,
            String decision,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            String routePath,
            Map<String, Object> routeQueryParams
    ) {}

    public record NotificationListResponse(
            List<UserNotificationListItem> items,
            long unreadCount
    ) {}
}
