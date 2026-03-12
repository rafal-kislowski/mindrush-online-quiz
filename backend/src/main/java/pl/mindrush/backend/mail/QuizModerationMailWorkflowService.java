package pl.mindrush.backend.mail;

import org.springframework.stereotype.Service;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.config.AppMailProperties;
import pl.mindrush.backend.quiz.Quiz;
import pl.mindrush.backend.quiz.QuizModerationStatus;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class QuizModerationMailWorkflowService {

    private final TransactionalMailService mailService;
    private final AppMailProperties mailProperties;

    public QuizModerationMailWorkflowService(
            TransactionalMailService mailService,
            AppMailProperties mailProperties
    ) {
        this.mailService = mailService;
        this.mailProperties = mailProperties;
    }

    public void sendModerationDecision(AppUser user, Quiz quiz, int questionIssueCount) {
        if (user == null || quiz == null) return;
        if (user.getEmail() == null || user.getEmail().isBlank()) return;

        QuizModerationStatus status = quiz.getModerationStatus();
        if (status != QuizModerationStatus.APPROVED && status != QuizModerationStatus.REJECTED) return;

        boolean approved = status == QuizModerationStatus.APPROVED;
        int normalizedIssueCount = Math.max(0, questionIssueCount);
        String subject = approved
                ? "MindRush - quiz approved"
                : "MindRush - quiz requires changes";

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("displayName", displayNameFor(user));
        vars.put("quizTitle", fallbackQuizTitle(quiz.getTitle()));
        vars.put("isApproved", approved);
        vars.put("decisionLabel", approved ? "Approved" : "Rejected");
        vars.put("decisionText", approved
                ? "Your quiz passed moderation and is now published."
                : "Your quiz review is complete. Open the quiz to see full admin feedback.");
        vars.put("decisionTone", approved ? "#6CE7AE" : "#FFBF8A");
        vars.put("questionIssueCount", normalizedIssueCount);
        vars.put("hasQuestionIssues", !approved && normalizedIssueCount > 0);
        vars.put("reason", safe(quiz.getModerationReason()));
        vars.put("actionUrl", buildModerationUrl(quiz.getId(), status, normalizedIssueCount));
        vars.put("actionLabel", approved ? "Open quiz" : "Open feedback");
        vars.put("supportEmail", safe(mailProperties.getSupportEmail()));

        mailService.sendTemplate(
                user.getEmail(),
                subject,
                "mail/quiz-moderation-decision",
                vars
        );
    }

    private String buildModerationUrl(Long quizId, QuizModerationStatus status, int questionIssueCount) {
        String base = trimTrailingSlash(normalizeBaseUrl(safe(mailProperties.getFrontendBaseUrl())));
        String path = "/library";
        if (quizId == null || quizId <= 0) {
            return base + path;
        }

        String targetTab = questionIssueCount > 0 ? "questions" : "details";
        StringBuilder url = new StringBuilder();
        url.append(base).append(path)
                .append("?openQuiz=").append(encode(String.valueOf(quizId)))
                .append("&moderationTab=").append(encode(targetTab));
        if (status == QuizModerationStatus.REJECTED) {
            url.append("&reopenModeration=1");
        }
        return url.toString();
    }

    private static String displayNameFor(AppUser user) {
        String displayName = safe(user.getDisplayName()).trim();
        if (!displayName.isEmpty()) return displayName;
        String email = safe(user.getEmail());
        int at = email.indexOf('@');
        if (at > 0) return email.substring(0, at);
        return "Player";
    }

    private static String fallbackQuizTitle(String title) {
        String normalized = safe(title).trim();
        return normalized.isEmpty() ? "Your quiz" : normalized;
    }

    private static String encode(String value) {
        return URLEncoder.encode(safe(value), StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String value) {
        String normalized = safe(value).trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? "http://localhost:4200" : normalized;
    }

    private static String normalizeBaseUrl(String rawBaseUrl) {
        String value = safe(rawBaseUrl).trim();
        if (value.isEmpty()) return "http://localhost:4200";
        if (value.contains("://")) return value;

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("localhost") || lower.startsWith("127.0.0.1")) {
            return "http://" + value;
        }
        return "https://" + value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
