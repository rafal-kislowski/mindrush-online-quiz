package pl.mindrush.backend.quiz;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import pl.mindrush.backend.AppRole;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Validated
@ConfigurationProperties(prefix = "app.library.policy")
public class QuizLibraryPolicyProperties {

    @Valid
    private TierLimits user = new TierLimits(20, 5, 3, 5, 50, 10, 5, 180, 50);

    @Valid
    private TierLimits premium = new TierLimits(60, 20, 10, 5, 100, 100, 5, 300, 100);

    @Valid
    private Media media = new Media(2L * 1024L * 1024L, List.of("image/jpeg", "image/png", "image/webp", "image/gif"));

    public TierLimits getUser() {
        return user;
    }

    public void setUser(TierLimits user) {
        this.user = user == null ? new TierLimits(20, 5, 3, 5, 50, 10, 5, 180, 50) : user;
    }

    public TierLimits getPremium() {
        return premium;
    }

    public void setPremium(TierLimits premium) {
        this.premium = premium == null ? new TierLimits(60, 20, 10, 5, 100, 100, 5, 300, 100) : premium;
    }

    public Media getMedia() {
        return media;
    }

    public void setMedia(Media media) {
        this.media = media == null
                ? new Media(2L * 1024L * 1024L, List.of("image/jpeg", "image/png", "image/webp", "image/gif"))
                : media;
    }

    public TierLimits forRoles(Set<AppRole> roles) {
        if (roles != null && roles.contains(AppRole.PREMIUM)) {
            return premium;
        }
        return user;
    }

    public static class TierLimits {
        @Min(1)
        private int maxOwnedQuizzes;
        @Min(1)
        private int maxPublishedQuizzes;
        @Min(1)
        private int maxPendingSubmissions;
        @Min(1)
        private int minQuestionsToSubmit;
        @Min(1)
        private int maxQuestionsPerQuiz;
        @Min(0)
        private int maxQuestionImagesPerQuiz;
        @Min(1)
        private int minQuestionTimeLimitSeconds;
        @Min(1)
        private int maxQuestionTimeLimitSeconds;
        @Min(1)
        private int maxQuestionsPerGame;

        public TierLimits() {
        }

        public TierLimits(
                int maxOwnedQuizzes,
                int maxPublishedQuizzes,
                int maxPendingSubmissions,
                int minQuestionsToSubmit,
                int maxQuestionsPerQuiz,
                int maxQuestionImagesPerQuiz,
                int minQuestionTimeLimitSeconds,
                int maxQuestionTimeLimitSeconds,
                int maxQuestionsPerGame
        ) {
            this.maxOwnedQuizzes = maxOwnedQuizzes;
            this.maxPublishedQuizzes = maxPublishedQuizzes;
            this.maxPendingSubmissions = maxPendingSubmissions;
            this.minQuestionsToSubmit = minQuestionsToSubmit;
            this.maxQuestionsPerQuiz = maxQuestionsPerQuiz;
            this.maxQuestionImagesPerQuiz = maxQuestionImagesPerQuiz;
            this.minQuestionTimeLimitSeconds = minQuestionTimeLimitSeconds;
            this.maxQuestionTimeLimitSeconds = maxQuestionTimeLimitSeconds;
            this.maxQuestionsPerGame = maxQuestionsPerGame;
        }

        public int getMaxOwnedQuizzes() {
            return maxOwnedQuizzes;
        }

        public void setMaxOwnedQuizzes(int maxOwnedQuizzes) {
            this.maxOwnedQuizzes = maxOwnedQuizzes;
        }

        public int getMaxPublishedQuizzes() {
            return maxPublishedQuizzes;
        }

        public void setMaxPublishedQuizzes(int maxPublishedQuizzes) {
            this.maxPublishedQuizzes = maxPublishedQuizzes;
        }

        public int getMaxPendingSubmissions() {
            return maxPendingSubmissions;
        }

        public void setMaxPendingSubmissions(int maxPendingSubmissions) {
            this.maxPendingSubmissions = maxPendingSubmissions;
        }

        public int getMinQuestionsToSubmit() {
            return minQuestionsToSubmit;
        }

        public void setMinQuestionsToSubmit(int minQuestionsToSubmit) {
            this.minQuestionsToSubmit = minQuestionsToSubmit;
        }

        public int getMaxQuestionsPerQuiz() {
            return maxQuestionsPerQuiz;
        }

        public void setMaxQuestionsPerQuiz(int maxQuestionsPerQuiz) {
            this.maxQuestionsPerQuiz = maxQuestionsPerQuiz;
        }

        public int getMaxQuestionImagesPerQuiz() {
            return maxQuestionImagesPerQuiz;
        }

        public void setMaxQuestionImagesPerQuiz(int maxQuestionImagesPerQuiz) {
            this.maxQuestionImagesPerQuiz = maxQuestionImagesPerQuiz;
        }

        public int getMinQuestionTimeLimitSeconds() {
            return minQuestionTimeLimitSeconds;
        }

        public void setMinQuestionTimeLimitSeconds(int minQuestionTimeLimitSeconds) {
            this.minQuestionTimeLimitSeconds = minQuestionTimeLimitSeconds;
        }

        public int getMaxQuestionTimeLimitSeconds() {
            return maxQuestionTimeLimitSeconds;
        }

        public void setMaxQuestionTimeLimitSeconds(int maxQuestionTimeLimitSeconds) {
            this.maxQuestionTimeLimitSeconds = maxQuestionTimeLimitSeconds;
        }

        public int getMaxQuestionsPerGame() {
            return maxQuestionsPerGame;
        }

        public void setMaxQuestionsPerGame(int maxQuestionsPerGame) {
            this.maxQuestionsPerGame = maxQuestionsPerGame;
        }
    }

    public static class Media {
        @Min(1)
        private long maxUploadBytes;
        private List<String> allowedMimeTypes = List.of();

        public Media() {
        }

        public Media(long maxUploadBytes, List<String> allowedMimeTypes) {
            this.maxUploadBytes = maxUploadBytes;
            this.allowedMimeTypes = allowedMimeTypes;
        }

        public long getMaxUploadBytes() {
            return maxUploadBytes;
        }

        public void setMaxUploadBytes(long maxUploadBytes) {
            this.maxUploadBytes = maxUploadBytes;
        }

        public List<String> getAllowedMimeTypes() {
            return allowedMimeTypes;
        }

        public void setAllowedMimeTypes(List<String> allowedMimeTypes) {
            if (allowedMimeTypes == null || allowedMimeTypes.isEmpty()) {
                this.allowedMimeTypes = List.of();
                return;
            }
            this.allowedMimeTypes = allowedMimeTypes.stream()
                    .filter(v -> v != null && !v.isBlank())
                    .map(v -> v.trim().toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
        }
    }
}
