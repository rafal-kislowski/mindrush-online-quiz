package pl.mindrush.backend.quiz;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.RefreshTokenRepository;
import pl.mindrush.backend.media.MediaStorageService;
import pl.mindrush.backend.notification.UserNotificationService;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class QuizLibraryService {

    private static final int DEFAULT_QUESTION_TIME_LIMIT_SECONDS = Quiz.DEFAULT_QUESTION_TIME_LIMIT_SECONDS;
    private static final int DEFAULT_QUESTIONS_PER_GAME = Quiz.DEFAULT_QUESTIONS_PER_GAME;
    private static final Pattern HEX_COLOR = Pattern.compile("^#(?:[0-9A-F]{3}|[0-9A-F]{6})$");
    private static final Pattern STORED_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,255}$");

    private final QuizRepository quizRepository;
    private final QuizCategoryRepository categoryRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAnswerOptionRepository optionRepository;
    private final QuizModerationIssueRepository moderationIssueRepository;
    private final QuizFavoriteRepository favoriteRepository;
    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final MediaStorageService mediaStorageService;
    private final QuizLibraryPolicyProperties policyProperties;
    private final UserNotificationService userNotificationService;

    public QuizLibraryService(
            QuizRepository quizRepository,
            QuizCategoryRepository categoryRepository,
            QuizQuestionRepository questionRepository,
            QuizAnswerOptionRepository optionRepository,
            QuizModerationIssueRepository moderationIssueRepository,
            QuizFavoriteRepository favoriteRepository,
            AppUserRepository appUserRepository,
            RefreshTokenRepository refreshTokenRepository,
            MediaStorageService mediaStorageService,
            QuizLibraryPolicyProperties policyProperties,
            UserNotificationService userNotificationService
    ) {
        this.quizRepository = quizRepository;
        this.categoryRepository = categoryRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.moderationIssueRepository = moderationIssueRepository;
        this.favoriteRepository = favoriteRepository;
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.mediaStorageService = mediaStorageService;
        this.policyProperties = policyProperties;
        this.userNotificationService = userNotificationService;
    }

    @Transactional(readOnly = true)
    public List<LibraryQuizListItem> listOwnedQuizzes(Long userId) {
        Set<Long> favoriteIds = favoriteQuizIdSet(userId);
        return quizRepository.findAllOwnedByUserId(userId).stream()
                .map(q -> toLibraryListItem(q, favoriteIds.contains(q.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public LibraryQuizDetail getOwnedQuizDetail(Long userId, Long quizId) {
        Quiz quiz = requireOwnedQuiz(userId, quizId);
        return toLibraryDetail(quiz, favoriteRepository.existsByUserIdAndQuizId(userId, quizId));
    }

    @Transactional(readOnly = true)
    public LibraryPolicy getPolicy(Long userId) {
        QuizLibraryPolicyProperties.TierLimits limits = limitsForUser(userId);
        long ownedCount = quizRepository.countByOwnerUserIdAndStatusNot(userId, QuizStatus.TRASHED);
        long publishedCount = quizRepository.countByOwnerUserIdAndStatusAndModerationStatus(
                userId,
                QuizStatus.ACTIVE,
                QuizModerationStatus.APPROVED
        );
        long pendingCount = quizRepository.countByOwnerUserIdAndModerationStatus(userId, QuizModerationStatus.PENDING);

        QuizLibraryPolicyProperties.Media media = policyProperties.getMedia();
        return new LibraryPolicy(
                limits.getMaxOwnedQuizzes(),
                limits.getMaxPublishedQuizzes(),
                limits.getMaxPendingSubmissions(),
                limits.getMinQuestionsToSubmit(),
                limits.getMaxQuestionsPerQuiz(),
                limits.getMinQuestionTimeLimitSeconds(),
                limits.getMaxQuestionTimeLimitSeconds(),
                limits.getMaxQuestionsPerGame(),
                media.getMaxUploadBytes(),
                normalizeAllowedMimeTypes(media.getAllowedMimeTypes()),
                ownedCount,
                publishedCount,
                pendingCount
        );
    }

    @Transactional(readOnly = true)
    public List<LibraryQuizListItem> listPublicQuizzes(Long userId) {
        Set<Long> favoriteIds = favoriteQuizIdSet(userId);
        return quizRepository.findAllWithCategoryByStatus(QuizStatus.ACTIVE).stream()
                .filter(QuizVisibilityRules::isPubliclyVisible)
                .sorted(Comparator.comparing(Quiz::getId).reversed())
                .map(q -> toLibraryListItem(q, favoriteIds.contains(q.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LibraryQuizListItem> listFavoriteQuizzes(Long userId) {
        List<QuizFavorite> favorites = favoriteRepository.findAllByUserId(userId);
        if (favorites.isEmpty()) return List.of();

        List<Long> quizIds = favorites.stream().map(QuizFavorite::getQuizId).distinct().toList();
        if (quizIds.isEmpty()) return List.of();

        Map<Long, Instant> favoriteAtByQuizId = new HashMap<>();
        for (QuizFavorite favorite : favorites) {
            favoriteAtByQuizId.put(favorite.getQuizId(), favorite.getCreatedAt());
        }

        Set<Long> favoriteIds = new HashSet<>(quizIds);
        return quizRepository.findAllWithCategoryByIdInAndStatusActive(quizIds).stream()
                .filter(QuizVisibilityRules::isPubliclyVisible)
                .sorted((a, b) -> {
                    Instant aAt = favoriteAtByQuizId.getOrDefault(a.getId(), Instant.EPOCH);
                    Instant bAt = favoriteAtByQuizId.getOrDefault(b.getId(), Instant.EPOCH);
                    return bAt.compareTo(aAt);
                })
                .map(q -> toLibraryListItem(q, favoriteIds.contains(q.getId())))
                .toList();
    }

    public FavoriteToggleResult toggleFavorite(Long userId, Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        if (!QuizVisibilityRules.isPubliclyVisible(quiz)) {
            throw new ResponseStatusException(NOT_FOUND, "Quiz not found");
        }

        boolean currentlyFavorite = favoriteRepository.existsByUserIdAndQuizId(userId, quizId);
        if (currentlyFavorite) {
            favoriteRepository.deleteByUserIdAndQuizId(userId, quizId);
            return new FavoriteToggleResult(false);
        }

        favoriteRepository.save(QuizFavorite.create(userId, quizId, Instant.now()));
        return new FavoriteToggleResult(true);
    }

    public Quiz createOwnedQuiz(
            Long userId,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            Integer questionTimeLimitSeconds,
            Integer questionsPerGame
    ) {
        QuizLibraryPolicyProperties.TierLimits limits = limitsForUser(userId);
        ensureCanCreateOwnedQuiz(userId, limits);

        String normalizedTitle = normalizeRequiredTitle(title);
        QuizCategory category = resolveCategory(categoryName);

        Quiz quiz = new Quiz(normalizedTitle, normalizeNullable(description), category);
        quiz.setSource(QuizSource.CUSTOM);
        quiz.setOwnerUserId(userId);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setModerationStatus(QuizModerationStatus.NONE);
        quiz.setModerationReason(null);
        quiz.setModerationUpdatedAt(null);
        applyAvatar(quiz, avatarImageUrl, avatarBgStart, avatarBgEnd, avatarTextColor);
        applyUserGameRules(quiz, questionTimeLimitSeconds, questionsPerGame, limits);
        return quizRepository.save(quiz);
    }

    public Quiz updateOwnedQuiz(
            Long userId,
            Long quizId,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            Integer questionTimeLimitSeconds,
            Integer questionsPerGame
    ) {
        QuizLibraryPolicyProperties.TierLimits limits = limitsForUser(userId);
        Quiz quiz = requireOwnedQuiz(userId, quizId);
        ensureQuizMutable(quiz);

        quiz.setTitle(normalizeRequiredTitle(title));
        quiz.setDescription(normalizeNullable(description));
        quiz.setCategory(resolveCategory(categoryName));
        applyAvatar(quiz, avatarImageUrl, avatarBgStart, avatarBgEnd, avatarTextColor);
        applyUserGameRules(quiz, questionTimeLimitSeconds, questionsPerGame, limits);

        resetModerationAfterOwnerEdit(quiz);

        return quizRepository.save(quiz);
    }

    public QuizQuestion addOwnedQuestion(Long userId, Long quizId, String prompt, String imageUrl, List<AnswerOptionInput> options) {
        QuizLibraryPolicyProperties.TierLimits limits = limitsForUser(userId);
        Quiz quiz = requireOwnedQuiz(userId, quizId);
        ensureQuizMutable(quiz);
        resetModerationAfterOwnerEdit(quiz);
        validateOptionsForCreate(options);

        long questionCount = questionRepository.countByQuizId(quizId);
        if (questionCount >= limits.getMaxQuestionsPerQuiz()) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "Quiz reached the maximum number of questions (" + limits.getMaxQuestionsPerQuiz() + ")"
            );
        }

        String normalizedPrompt = normalizeRequiredPrompt(prompt);
        int orderIndex = (int) questionCount;
        QuizQuestion question = questionRepository.save(new QuizQuestion(quiz, normalizedPrompt, orderIndex));
        question.setImageUrl(normalizeNullableStoredMediaUrl(imageUrl, "Question image URL"));

        for (int i = 0; i < options.size(); i++) {
            AnswerOptionInput input = options.get(i);
            QuizAnswerOption option = new QuizAnswerOption(
                    question,
                    normalizeOptionText(input == null ? null : input.text()),
                    input != null && input.correct(),
                    i
            );
            option.setImageUrl(normalizeNullableStoredMediaUrl(input == null ? null : input.imageUrl(), "Option image URL"));
            optionRepository.save(option);
        }

        return question;
    }

    public void updateOwnedQuestion(
            Long userId,
            Long quizId,
            Long questionId,
            String prompt,
            String imageUrl,
            List<AnswerOptionUpdateInput> options
    ) {
        Quiz quiz = requireOwnedQuiz(userId, quizId);
        ensureQuizMutable(quiz);
        resetModerationAfterOwnerEdit(quiz);
        validateOptionsForUpdate(options);

        QuizQuestion question = questionRepository.findByIdAndQuizId(questionId, quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Question not found"));

        question.setPrompt(normalizeRequiredPrompt(prompt));
        question.setImageUrl(normalizeNullableStoredMediaUrl(imageUrl, "Question image URL"));

        List<QuizAnswerOption> existing = optionRepository.findAllByQuestionIdOrderByOrderIndexAsc(questionId);
        if (existing.size() != 4) {
            throw new ResponseStatusException(BAD_REQUEST, "Existing question must have exactly 4 answer options");
        }

        Map<Long, QuizAnswerOption> existingById = existing.stream()
                .collect(Collectors.toMap(QuizAnswerOption::getId, o -> o));
        Set<Long> existingIds = existingById.keySet();
        Set<Long> requestedIds = options.stream().map(AnswerOptionUpdateInput::id).collect(Collectors.toSet());
        if (!existingIds.equals(requestedIds)) {
            throw new ResponseStatusException(BAD_REQUEST, "Answer option ids do not match existing question options");
        }

        for (int i = 0; i < options.size(); i++) {
            AnswerOptionUpdateInput input = options.get(i);
            QuizAnswerOption option = existingById.get(input.id());
            option.setText(normalizeOptionText(input.text()));
            option.setImageUrl(normalizeNullableStoredMediaUrl(input.imageUrl(), "Option image URL"));
            option.setCorrect(input.correct());
            option.setOrderIndex(i);
            optionRepository.save(option);
        }

        questionRepository.save(question);
    }

    public void deleteOwnedQuestion(Long userId, Long quizId, Long questionId) {
        Quiz quiz = requireOwnedQuiz(userId, quizId);
        ensureQuizMutable(quiz);
        resetModerationAfterOwnerEdit(quiz);

        QuizQuestion question = questionRepository.findByIdAndQuizId(questionId, quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Question not found"));
        optionRepository.deleteAllByQuestionId(questionId);
        questionRepository.delete(question);
    }

    public Quiz trashOwnedQuiz(Long userId, Long quizId) {
        Quiz quiz = requireOwnedQuiz(userId, quizId);
        quiz.setStatus(QuizStatus.TRASHED);
        quiz.setModerationStatus(QuizModerationStatus.NONE);
        quiz.setModerationReason(null);
        quiz.setModerationUpdatedAt(null);
        clearModerationIssues(quiz.getId());
        return quizRepository.save(quiz);
    }

    public Quiz setOwnedQuizStatus(Long userId, Long quizId, QuizStatus status) {
        if (status == null) throw new ResponseStatusException(BAD_REQUEST, "Status is required");

        Quiz quiz = requireOwnedQuiz(userId, quizId);
        if (status == QuizStatus.ACTIVE) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "Publishing is available only after admin approval. Submit the quiz for moderation first."
            );
        }

        quiz.setStatus(status);
        if (status != QuizStatus.ACTIVE && quiz.getModerationStatus() == QuizModerationStatus.APPROVED) {
            quiz.setModerationStatus(QuizModerationStatus.NONE);
            quiz.setModerationReason(null);
            quiz.setModerationUpdatedAt(null);
            clearModerationIssues(quiz.getId());
        }
        if (status == QuizStatus.TRASHED) {
            quiz.setModerationStatus(QuizModerationStatus.NONE);
            quiz.setModerationReason(null);
            quiz.setModerationUpdatedAt(null);
            clearModerationIssues(quiz.getId());
        }
        return quizRepository.save(quiz);
    }

    public void purgeOwnedQuiz(Long userId, Long quizId) {
        Quiz quiz = requireOwnedQuiz(userId, quizId);
        if (quiz.getStatus() != QuizStatus.TRASHED) {
            throw new ResponseStatusException(CONFLICT, "Only trashed quiz can be permanently deleted");
        }

        List<QuizQuestion> questions = questionRepository.findAllByQuizIdOrderByOrderIndexAsc(quizId);
        List<Long> qIds = questions.stream().map(QuizQuestion::getId).toList();

        Set<String> mediaUrls = new HashSet<>();
        if (quiz.getAvatarImageUrl() != null) mediaUrls.add(quiz.getAvatarImageUrl());
        for (QuizQuestion q : questions) {
            if (q.getImageUrl() != null) mediaUrls.add(q.getImageUrl());
        }
        if (!qIds.isEmpty()) {
            optionRepository.findAllByQuestionIdInOrderByQuestionIdAscOrderIndexAsc(qIds).forEach(o -> {
                if (o.getImageUrl() != null) mediaUrls.add(o.getImageUrl());
            });
        }

        favoriteRepository.deleteAllByQuizId(quizId);
        clearModerationIssues(quizId);
        if (!qIds.isEmpty()) optionRepository.deleteAllByQuestionIdIn(qIds);
        questionRepository.deleteAll(questions);
        quizRepository.delete(quiz);

        for (String url : mediaUrls) {
            try {
                mediaStorageService.deleteIfStoredUrl(url);
            } catch (Exception ignored) {
            }
        }
    }

    public Quiz submitOwnedQuizForModeration(Long userId, Long quizId) {
        QuizLibraryPolicyProperties.TierLimits limits = limitsForUser(userId);
        Quiz quiz = requireOwnedQuiz(userId, quizId);
        ensureQuizMutable(quiz);
        if (quiz.getModerationStatus() == QuizModerationStatus.PENDING) {
            throw new ResponseStatusException(CONFLICT, "Quiz is already pending moderation");
        }

        long questionCount = questionRepository.countByQuizId(quizId);
        ensureCanSubmitQuiz(userId, quiz, questionCount, limits);

        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setModerationStatus(QuizModerationStatus.PENDING);
        quiz.setModerationReason(null);
        quiz.setModerationUpdatedAt(Instant.now());
        clearModerationIssues(quiz.getId());
        return quizRepository.save(quiz);
    }

    @Transactional(readOnly = true)
    public List<AdminSubmissionListItem> listPendingSubmissions() {
        List<Quiz> submissions = new ArrayList<>();
        submissions.addAll(quizRepository.findAllWithCategoryByModerationStatus(QuizModerationStatus.PENDING));
        submissions.addAll(quizRepository.findAllWithCategoryByModerationStatus(QuizModerationStatus.APPROVED));
        submissions.addAll(quizRepository.findAllWithCategoryByModerationStatus(QuizModerationStatus.REJECTED));

        Set<Long> ownerIds = submissions.stream()
                .map(Quiz::getOwnerUserId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        Map<Long, AppUser> ownerById = new HashMap<>();
        if (!ownerIds.isEmpty()) {
            for (AppUser user : appUserRepository.findAllById(ownerIds)) {
                ownerById.put(user.getId(), user);
            }
        }

        return submissions.stream()
                .map(q -> {
                    AppUser owner = ownerById.get(q.getOwnerUserId());
                    String ownerName = owner == null ? "Unknown" : owner.getDisplayName();
                    boolean ownerIsPremium = owner != null && owner.getRoles().contains(AppRole.PREMIUM);
                    return new AdminSubmissionListItem(
                            q.getId(),
                            q.getTitle(),
                            q.getCategory() == null ? null : q.getCategory().getName(),
                            ownerName,
                            ownerIsPremium,
                            q.getAvatarImageUrl(),
                            q.getAvatarBgStart(),
                            q.getAvatarBgEnd(),
                            q.getAvatarTextColor(),
                            questionRepository.countByQuizId(q.getId()),
                            q.getStatus(),
                            q.getModerationStatus(),
                            q.getVersion(),
                            q.getModerationUpdatedAt()
                    );
                })
                .sorted(Comparator
                        .comparing(
                                (AdminSubmissionListItem item) -> moderationStatusOrder(item.moderationStatus())
                        )
                        .thenComparing(
                                AdminSubmissionListItem::moderationUpdatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())
                        )
                        .thenComparing(AdminSubmissionListItem::id, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminSubmissionDetail getSubmissionDetail(Long quizId) {
        Quiz quiz = requireSubmissionForAdmin(quizId);
        return toAdminSubmissionDetail(quiz);
    }

    public AdminSubmissionDetail removeSubmissionQuestionImage(Long quizId, Long questionId) {
        Quiz quiz = requireSubmissionForAdmin(quizId);
        QuizQuestion question = questionRepository.findByIdAndQuizId(questionId, quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Question not found"));

        String removedUrl = question.getImageUrl();
        if (removedUrl != null && !removedUrl.isBlank()) {
            question.setImageUrl(null);
            questionRepository.save(question);
            deleteStoredMediaQuietly(removedUrl);
        }
        return toAdminSubmissionDetail(quiz);
    }

    public AdminSubmissionDetail removeSubmissionAvatarImage(Long quizId) {
        Quiz quiz = requireSubmissionForAdmin(quizId);
        String removedUrl = quiz.getAvatarImageUrl();
        if (removedUrl != null && !removedUrl.isBlank()) {
            quiz.setAvatarImageUrl(null);
            quizRepository.save(quiz);
            deleteStoredMediaQuietly(removedUrl);
        }
        return toAdminSubmissionDetail(quiz);
    }

    public AdminSubmissionDetail removeSubmissionOptionImage(Long quizId, Long questionId, Long optionId) {
        Quiz quiz = requireSubmissionForAdmin(quizId);
        questionRepository.findByIdAndQuizId(questionId, quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Question not found"));
        QuizAnswerOption option = optionRepository.findByIdAndQuestionId(optionId, questionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Answer option not found"));

        String removedUrl = option.getImageUrl();
        if (removedUrl != null && !removedUrl.isBlank()) {
            option.setImageUrl(null);
            optionRepository.save(option);
            deleteStoredMediaQuietly(removedUrl);
        }
        return toAdminSubmissionDetail(quiz);
    }

    public OwnerModerationResult banSubmissionOwner(Long quizId) {
        Quiz quiz = requireSubmissionForAdmin(quizId);
        Long ownerUserId = quiz.getOwnerUserId();
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Submission has no owner account");
        }

        AppUser owner = appUserRepository.findById(ownerUserId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Owner account not found"));
        if (owner.getRoles().contains(AppRole.ADMIN)) {
            throw new ResponseStatusException(FORBIDDEN, "Admin account cannot be banned");
        }

        Set<AppRole> updatedRoles = new HashSet<>(owner.getRoles());
        if (!updatedRoles.contains(AppRole.BANNED)) {
            updatedRoles.add(AppRole.BANNED);
            owner.setRoles(updatedRoles);
            owner = appUserRepository.save(owner);
        }

        refreshTokenRepository.deleteAllByUser_Id(owner.getId());
        return toOwnerModerationResult(owner);
    }

    public OwnerModerationResult unbanSubmissionOwner(Long quizId) {
        Quiz quiz = requireSubmissionForAdmin(quizId);
        Long ownerUserId = quiz.getOwnerUserId();
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Submission has no owner account");
        }

        AppUser owner = appUserRepository.findById(ownerUserId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Owner account not found"));

        Set<AppRole> updatedRoles = new HashSet<>(owner.getRoles());
        if (updatedRoles.remove(AppRole.BANNED)) {
            owner.setRoles(updatedRoles);
            owner = appUserRepository.save(owner);
        }

        return toOwnerModerationResult(owner);
    }

    public Quiz approveSubmission(Long quizId, Long expectedSubmissionVersion) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        ensureSubmissionStillCurrent(quiz, expectedSubmissionVersion, QuizModerationStatus.PENDING, "Quiz is not pending moderation");

        long questionCount = questionRepository.countByQuizId(quizId);
        if (questionCount <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Quiz must have at least 1 question to publish");
        }

        Long ownerUserId = quiz.getOwnerUserId();
        if (ownerUserId != null) {
            QuizLibraryPolicyProperties.TierLimits limits = limitsForUser(ownerUserId);
            if (questionCount < limits.getMinQuestionsToSubmit()) {
                throw new ResponseStatusException(
                        BAD_REQUEST,
                        "Quiz must have at least " + limits.getMinQuestionsToSubmit() + " questions to publish"
                );
            }

            long publishedCount = quizRepository.countByOwnerUserIdAndStatusAndModerationStatus(
                    ownerUserId,
                    QuizStatus.ACTIVE,
                    QuizModerationStatus.APPROVED
            );
            if (publishedCount >= limits.getMaxPublishedQuizzes()) {
                throw new ResponseStatusException(
                        CONFLICT,
                        "Owner reached the published quiz limit (" + limits.getMaxPublishedQuizzes() + ")"
                );
            }
        }

        quiz.setStatus(QuizStatus.ACTIVE);
        quiz.setModerationStatus(QuizModerationStatus.APPROVED);
        quiz.setModerationReason(null);
        quiz.setModerationUpdatedAt(Instant.now());
        clearModerationIssues(quiz.getId());
        Quiz saved = quizRepository.save(quiz);
        userNotificationService.createModerationDecisionNotification(saved, 0);
        return saved;
    }

    public Quiz undoApprovedSubmission(Long quizId, Long expectedSubmissionVersion) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        ensureSubmissionStillCurrent(quiz, expectedSubmissionVersion, QuizModerationStatus.APPROVED, "Quiz is not passed");

        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setModerationStatus(QuizModerationStatus.PENDING);
        quiz.setModerationReason(null);
        quiz.setModerationUpdatedAt(Instant.now());
        clearModerationIssues(quiz.getId());
        return quizRepository.save(quiz);
    }

    public Quiz rejectSubmission(
            Long quizId,
            Long expectedSubmissionVersion,
            String reason,
            List<QuestionIssueInput> questionIssues
    ) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        ensureSubmissionStillCurrent(quiz, expectedSubmissionVersion, QuizModerationStatus.PENDING, "Quiz is not pending moderation");

        String normalizedReason = normalizeRequiredReason(reason);
        List<QuestionIssueInput> normalizedQuestionIssues = normalizeQuestionIssuesForQuiz(quiz, questionIssues);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setModerationStatus(QuizModerationStatus.REJECTED);
        quiz.setModerationReason(normalizedReason);
        quiz.setModerationUpdatedAt(Instant.now());
        Quiz saved = quizRepository.save(quiz);

        clearModerationIssues(saved.getId());
        if (!normalizedQuestionIssues.isEmpty()) {
            normalizedQuestionIssues.forEach(issue -> moderationIssueRepository.save(
                    new QuizModerationIssue(saved, issue.questionId(), issue.message())
            ));
        }

        userNotificationService.createModerationDecisionNotification(saved, normalizedQuestionIssues.size());

        return saved;
    }

    private Quiz requireSubmissionForAdmin(Long quizId) {
        Quiz quiz = quizRepository.findByIdWithCategory(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        if (!hasModerationHistory(quiz)) {
            throw new ResponseStatusException(CONFLICT, "Quiz has no moderation history");
        }
        return quiz;
    }

    private AdminSubmissionDetail toAdminSubmissionDetail(Quiz quiz) {
        AppUser owner = quiz.getOwnerUserId() == null
                ? null
                : appUserRepository.findById(quiz.getOwnerUserId()).orElse(null);
        LibraryQuizDetail detail = toLibraryDetail(quiz, false);
        return new AdminSubmissionDetail(
                detail.id(),
                detail.title(),
                detail.description(),
                detail.categoryName(),
                detail.avatarImageUrl(),
                detail.avatarBgStart(),
                detail.avatarBgEnd(),
                detail.avatarTextColor(),
                detail.questionTimeLimitSeconds(),
                detail.questionsPerGame(),
                detail.status(),
                detail.source(),
                detail.moderationStatus(),
                detail.moderationReason(),
                owner == null ? null : owner.getId(),
                owner == null ? null : owner.getDisplayName(),
                owner == null ? null : owner.getEmail(),
                owner != null && owner.getRoles().contains(AppRole.BANNED),
                owner == null ? List.of() : owner.getRoles().stream().map(Enum::name).sorted().toList(),
                detail.questions(),
                quiz.getVersion()
        );
    }

    private static boolean hasModerationHistory(Quiz quiz) {
        QuizModerationStatus status = quiz.getModerationStatus();
        return status == QuizModerationStatus.PENDING
                || status == QuizModerationStatus.APPROVED
                || status == QuizModerationStatus.REJECTED;
    }

    private OwnerModerationResult toOwnerModerationResult(AppUser owner) {
        return new OwnerModerationResult(
                owner.getId(),
                owner.getDisplayName(),
                owner.getEmail(),
                owner.getRoles().contains(AppRole.BANNED),
                owner.getRoles().stream().map(Enum::name).sorted().toList()
        );
    }

    private void deleteStoredMediaQuietly(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) return;
        try {
            mediaStorageService.deleteIfStoredUrl(mediaUrl);
        } catch (Exception ignored) {
        }
    }

    private Set<Long> favoriteQuizIdSet(Long userId) {
        return favoriteRepository.findAllByUserId(userId).stream()
                .map(QuizFavorite::getQuizId)
                .collect(Collectors.toSet());
    }

    private Quiz requireOwnedQuiz(Long userId, Long quizId) {
        Quiz quiz = quizRepository.findByIdWithCategory(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        if (!userId.equals(quiz.getOwnerUserId())) {
            throw new ResponseStatusException(FORBIDDEN, "You can manage only your own quizzes");
        }
        return quiz;
    }

    private QuizLibraryPolicyProperties.TierLimits limitsForUser(Long userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        return policyProperties.forRoles(user.getRoles());
    }

    private void ensureCanCreateOwnedQuiz(Long userId, QuizLibraryPolicyProperties.TierLimits limits) {
        long owned = quizRepository.countByOwnerUserIdAndStatusNot(userId, QuizStatus.TRASHED);
        if (owned >= limits.getMaxOwnedQuizzes()) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "Owned quiz limit reached (" + limits.getMaxOwnedQuizzes() + "). Move old quizzes to trash or delete them."
            );
        }
    }

    private void ensureCanSubmitQuiz(
            Long userId,
            Quiz quiz,
            long questionCount,
            QuizLibraryPolicyProperties.TierLimits limits
    ) {
        if (questionCount < limits.getMinQuestionsToSubmit()) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Quiz must have at least " + limits.getMinQuestionsToSubmit() + " questions to submit"
            );
        }

        long pendingCount = quizRepository.countByOwnerUserIdAndModerationStatus(userId, QuizModerationStatus.PENDING);
        if (quiz.getModerationStatus() != QuizModerationStatus.PENDING
                && pendingCount >= limits.getMaxPendingSubmissions()) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "Pending submission limit reached (" + limits.getMaxPendingSubmissions() + ")"
            );
        }

        boolean alreadyPublished = quiz.getStatus() == QuizStatus.ACTIVE
                && quiz.getModerationStatus() == QuizModerationStatus.APPROVED;
        long publishedCount = quizRepository.countByOwnerUserIdAndStatusAndModerationStatus(
                userId,
                QuizStatus.ACTIVE,
                QuizModerationStatus.APPROVED
        );
        if (!alreadyPublished && publishedCount >= limits.getMaxPublishedQuizzes()) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "Published quiz limit reached (" + limits.getMaxPublishedQuizzes() + ")"
            );
        }
    }

    private static void ensureQuizMutable(Quiz quiz) {
        if (quiz.getStatus() == QuizStatus.TRASHED) {
            throw new ResponseStatusException(CONFLICT, "Quiz is in trash");
        }
    }

    private static int moderationStatusOrder(QuizModerationStatus status) {
        if (status == QuizModerationStatus.PENDING) return 0;
        if (status == QuizModerationStatus.APPROVED) return 1;
        if (status == QuizModerationStatus.REJECTED) return 2;
        return 3;
    }

    private static void ensureSubmissionStillCurrent(
            Quiz quiz,
            Long expectedSubmissionVersion,
            QuizModerationStatus requiredStatus,
            String statusConflictMessage
    ) {
        if (quiz.getModerationStatus() != requiredStatus) {
            throw new ResponseStatusException(CONFLICT, statusConflictMessage);
        }
        if (expectedSubmissionVersion == null || expectedSubmissionVersion < 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Submission version is required");
        }
        if (!quiz.getVersion().equals(expectedSubmissionVersion)) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "Submission changed during review. Reload the queue and review the latest version."
            );
        }
    }

    private void resetModerationAfterOwnerEdit(Quiz quiz) {
        if (quiz.getModerationStatus() == QuizModerationStatus.PENDING
                || quiz.getModerationStatus() == QuizModerationStatus.APPROVED
                || quiz.getStatus() == QuizStatus.ACTIVE) {
            quiz.setStatus(QuizStatus.DRAFT);
            quiz.setModerationStatus(QuizModerationStatus.NONE);
            quiz.setModerationReason(null);
            quiz.setModerationUpdatedAt(null);
            clearModerationIssues(quiz.getId());
        }
    }

    private QuizCategory resolveCategory(String categoryName) {
        String normalized = normalizeNullable(categoryName);
        if (normalized == null) return null;
        return categoryRepository.findByName(normalized)
                .orElseGet(() -> categoryRepository.save(new QuizCategory(normalized)));
    }

    private static String normalizeRequiredTitle(String title) {
        String normalized = normalizeNullable(title);
        if (normalized == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Title is required");
        }
        if (normalized.length() > 120) {
            throw new ResponseStatusException(BAD_REQUEST, "Title is too long");
        }
        return normalized;
    }

    private static String normalizeRequiredPrompt(String prompt) {
        String normalized = normalizeNullable(prompt);
        if (normalized == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Prompt is required");
        }
        if (normalized.length() > 500) {
            throw new ResponseStatusException(BAD_REQUEST, "Prompt is too long");
        }
        return normalized;
    }

    private static String normalizeRequiredReason(String reason) {
        String normalized = normalizeNullable(reason);
        if (normalized == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Rejection reason is required");
        }
        if (normalized.length() > 500) {
            throw new ResponseStatusException(BAD_REQUEST, "Rejection reason is too long");
        }
        return normalized;
    }

    private static String normalizeRequiredIssueMessage(String message) {
        String normalized = normalizeNullable(message);
        if (normalized == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Issue message is required");
        }
        if (normalized.length() > 500) {
            throw new ResponseStatusException(BAD_REQUEST, "Issue message is too long");
        }
        return normalized;
    }

    private List<QuestionIssueInput> normalizeQuestionIssuesForQuiz(Quiz quiz, List<QuestionIssueInput> questionIssues) {
        if (questionIssues == null || questionIssues.isEmpty()) return List.of();

        Set<Long> validQuestionIds = new HashSet<>(questionRepository.findIdsByQuizIdOrderByOrderIndexAsc(quiz.getId()));
        Map<Long, String> deduped = new LinkedHashMap<>();

        for (QuestionIssueInput issue : questionIssues) {
            if (issue == null || issue.questionId() == null || issue.questionId() <= 0) {
                throw new ResponseStatusException(BAD_REQUEST, "Question issue requires a valid question id");
            }
            if (!validQuestionIds.contains(issue.questionId())) {
                throw new ResponseStatusException(BAD_REQUEST, "Question issue references a question outside this quiz");
            }
            String message = normalizeRequiredIssueMessage(issue.message());
            deduped.putIfAbsent(issue.questionId(), message);
        }

        return deduped.entrySet().stream()
                .map(entry -> new QuestionIssueInput(entry.getKey(), entry.getValue()))
                .toList();
    }

    private void clearModerationIssues(Long quizId) {
        if (quizId == null || quizId <= 0) return;
        moderationIssueRepository.deleteAllByQuizId(quizId);
    }

    private static String normalizeOptionText(String text) {
        String normalized = normalizeNullable(text);
        if (normalized != null && normalized.length() > 200) {
            throw new ResponseStatusException(BAD_REQUEST, "Option text is too long");
        }
        return normalized == null ? "" : normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String normalizeNullableStoredMediaUrl(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) return null;

        String path = normalized;
        try {
            URI uri = URI.create(normalized);
            if (uri.getScheme() != null) {
                path = uri.getPath();
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_REQUEST, fieldName + " is invalid");
        }

        if (path == null || !path.startsWith("/media/")) {
            throw new ResponseStatusException(BAD_REQUEST, fieldName + " must reference uploaded media");
        }
        String name = path.substring("/media/".length());
        if (!STORED_FILE_NAME.matcher(name).matches() || name.contains("..")) {
            throw new ResponseStatusException(BAD_REQUEST, fieldName + " is invalid");
        }
        return "/media/" + name;
    }

    private static String normalizeNullableColor(String color, String fieldName) {
        String normalized = normalizeNullable(color);
        if (normalized == null) return null;
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!HEX_COLOR.matcher(upper).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, fieldName + " must be a HEX color");
        }
        if (upper.length() == 4) {
            return "#"
                    + upper.charAt(1) + upper.charAt(1)
                    + upper.charAt(2) + upper.charAt(2)
                    + upper.charAt(3) + upper.charAt(3);
        }
        return upper;
    }

    private static void validateOptionsForCreate(List<AnswerOptionInput> options) {
        if (options == null || options.size() != 4) {
            throw new ResponseStatusException(BAD_REQUEST, "Exactly 4 answer options are required");
        }

        long correctCount = options.stream().filter(o -> o != null && o.correct()).count();
        if (correctCount != 1) {
            throw new ResponseStatusException(BAD_REQUEST, "Exactly 1 answer option must be correct");
        }

        for (AnswerOptionInput option : options) {
            String text = option == null ? null : normalizeNullable(option.text());
            String imageUrl = option == null ? null : normalizeNullable(option.imageUrl());
            if (text == null && imageUrl == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Each answer option must have text or an image");
            }
            if (text != null && text.length() > 200) {
                throw new ResponseStatusException(BAD_REQUEST, "Option text is too long");
            }
        }
    }

    private static void validateOptionsForUpdate(List<AnswerOptionUpdateInput> options) {
        if (options == null || options.size() != 4) {
            throw new ResponseStatusException(BAD_REQUEST, "Exactly 4 answer options are required");
        }

        long correctCount = options.stream().filter(o -> o != null && o.correct()).count();
        if (correctCount != 1) {
            throw new ResponseStatusException(BAD_REQUEST, "Exactly 1 answer option must be correct");
        }

        for (AnswerOptionUpdateInput option : options) {
            if (option == null || option.id() == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Answer option id is required");
            }
            String text = normalizeNullable(option.text());
            String imageUrl = normalizeNullable(option.imageUrl());
            if (text == null && imageUrl == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Each answer option must have text or an image");
            }
            if (text != null && text.length() > 200) {
                throw new ResponseStatusException(BAD_REQUEST, "Option text is too long");
            }
        }
    }

    private static void applyAvatar(
            Quiz quiz,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor
    ) {
        quiz.setAvatarImageUrl(normalizeNullableStoredMediaUrl(avatarImageUrl, "Avatar image URL"));
        quiz.setAvatarBgStart(normalizeNullableColor(avatarBgStart, "Avatar start color"));
        quiz.setAvatarBgEnd(normalizeNullableColor(avatarBgEnd, "Avatar end color"));
        quiz.setAvatarTextColor(normalizeNullableColor(avatarTextColor, "Avatar text color"));
    }

    private static void applyUserGameRules(
            Quiz quiz,
            Integer questionTimeLimitSeconds,
            Integer questionsPerGame,
            QuizLibraryPolicyProperties.TierLimits limits
    ) {
        int normalizedLimit = questionTimeLimitSeconds == null || questionTimeLimitSeconds <= 0
                ? DEFAULT_QUESTION_TIME_LIMIT_SECONDS
                : questionTimeLimitSeconds;
        if (normalizedLimit < limits.getMinQuestionTimeLimitSeconds()
                || normalizedLimit > limits.getMaxQuestionTimeLimitSeconds()) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Question time limit must be between "
                            + limits.getMinQuestionTimeLimitSeconds()
                            + " and "
                            + limits.getMaxQuestionTimeLimitSeconds()
                            + " seconds"
            );
        }

        int normalizedQuestionsPerGame = questionsPerGame == null || questionsPerGame <= 0
                ? DEFAULT_QUESTIONS_PER_GAME
                : questionsPerGame;
        if (normalizedQuestionsPerGame < 1 || normalizedQuestionsPerGame > limits.getMaxQuestionsPerGame()) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Questions per game must be between 1 and " + limits.getMaxQuestionsPerGame()
            );
        }

        quiz.setGameMode(GameMode.CASUAL);
        quiz.setIncludeInRanking(false);
        quiz.setXpEnabled(true);
        quiz.setQuestionTimeLimitSeconds(normalizedLimit);
        quiz.setQuestionsPerGame(normalizedQuestionsPerGame);
    }

    private static List<String> normalizeAllowedMimeTypes(List<String> mimeTypes) {
        if (mimeTypes == null || mimeTypes.isEmpty()) return List.of();
        return mimeTypes.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private LibraryQuizListItem toLibraryListItem(Quiz quiz, boolean favorite) {
        long moderationQuestionIssueCount = moderationIssueRepository.countByQuizId(quiz.getId());
        return new LibraryQuizListItem(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getCategory() == null ? null : quiz.getCategory().getName(),
                quiz.getAvatarImageUrl(),
                quiz.getAvatarBgStart(),
                quiz.getAvatarBgEnd(),
                quiz.getAvatarTextColor(),
                quiz.getQuestionTimeLimitSeconds(),
                quiz.getQuestionsPerGame(),
                quiz.getStatus(),
                quiz.getSource(),
                quiz.getModerationStatus(),
                quiz.getModerationReason(),
                quiz.getModerationUpdatedAt(),
                moderationQuestionIssueCount,
                favorite,
                questionRepository.countByQuizId(quiz.getId())
        );
    }

    private LibraryQuizDetail toLibraryDetail(Quiz quiz, boolean favorite) {
        List<QuizQuestion> questions = questionRepository.findAllByQuizIdOrderByOrderIndexAsc(quiz.getId());
        List<Long> questionIds = questions.stream().map(QuizQuestion::getId).toList();
        List<LibraryModerationQuestionIssue> moderationQuestionIssues = moderationIssueRepository
                .findAllByQuizIdOrderByIdAsc(quiz.getId()).stream()
                .map(issue -> new LibraryModerationQuestionIssue(issue.getQuestionId(), issue.getMessage()))
                .toList();

        Map<Long, List<LibraryAnswerOption>> optionsByQuestionId = new HashMap<>();
        if (!questionIds.isEmpty()) {
            optionRepository.findAllByQuestionIdInOrderByQuestionIdAscOrderIndexAsc(questionIds).forEach(option -> {
                optionsByQuestionId.computeIfAbsent(option.getQuestion().getId(), ignored -> new ArrayList<>())
                        .add(new LibraryAnswerOption(
                                option.getId(),
                                option.getOrderIndex(),
                                option.getText(),
                                option.getImageUrl(),
                                option.isCorrect()
                        ));
            });
        }

        List<LibraryQuestion> questionDtos = questions.stream()
                .map(question -> new LibraryQuestion(
                        question.getId(),
                        question.getOrderIndex(),
                        question.getPrompt(),
                        question.getImageUrl(),
                        optionsByQuestionId.getOrDefault(question.getId(), List.of())
                ))
                .toList();

        return new LibraryQuizDetail(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getCategory() == null ? null : quiz.getCategory().getName(),
                quiz.getAvatarImageUrl(),
                quiz.getAvatarBgStart(),
                quiz.getAvatarBgEnd(),
                quiz.getAvatarTextColor(),
                quiz.getQuestionTimeLimitSeconds(),
                quiz.getQuestionsPerGame(),
                quiz.getStatus(),
                quiz.getSource(),
                quiz.getModerationStatus(),
                quiz.getModerationReason(),
                quiz.getModerationUpdatedAt(),
                moderationQuestionIssues,
                favorite,
                questionDtos
        );
    }

    public record AnswerOptionInput(String text, String imageUrl, boolean correct) {}

    public record AnswerOptionUpdateInput(Long id, String text, String imageUrl, boolean correct) {}

    public record QuestionIssueInput(Long questionId, String message) {}

    public record FavoriteToggleResult(boolean favorite) {}

    public record LibraryPolicy(
            int maxOwnedQuizzes,
            int maxPublishedQuizzes,
            int maxPendingSubmissions,
            int minQuestionsToSubmit,
            int maxQuestionsPerQuiz,
            int minQuestionTimeLimitSeconds,
            int maxQuestionTimeLimitSeconds,
            int maxQuestionsPerGame,
            long maxUploadBytes,
            List<String> allowedUploadMimeTypes,
            long ownedCount,
            long publishedCount,
            long pendingCount
    ) {}

    public record LibraryQuizListItem(
            Long id,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            Integer questionTimeLimitSeconds,
            Integer questionsPerGame,
            QuizStatus status,
            QuizSource source,
            QuizModerationStatus moderationStatus,
            String moderationReason,
            Instant moderationUpdatedAt,
            long moderationQuestionIssueCount,
            boolean favorite,
            long questionCount
    ) {}

    public record LibraryQuizDetail(
            Long id,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            Integer questionTimeLimitSeconds,
            Integer questionsPerGame,
            QuizStatus status,
            QuizSource source,
            QuizModerationStatus moderationStatus,
            String moderationReason,
            Instant moderationUpdatedAt,
            List<LibraryModerationQuestionIssue> moderationQuestionIssues,
            boolean favorite,
            List<LibraryQuestion> questions
    ) {}

    public record LibraryModerationQuestionIssue(
            Long questionId,
            String message
    ) {}

    public record LibraryQuestion(
            Long id,
            int orderIndex,
            String prompt,
            String imageUrl,
            List<LibraryAnswerOption> options
    ) {}

    public record LibraryAnswerOption(
            Long id,
            int orderIndex,
            String text,
            String imageUrl,
            boolean correct
    ) {}

    public record AdminSubmissionListItem(
            Long id,
            String title,
            String categoryName,
            String ownerDisplayName,
            boolean ownerIsPremium,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            long questionCount,
            QuizStatus status,
            QuizModerationStatus moderationStatus,
            Long submissionVersion,
            Instant moderationUpdatedAt
    ) {}

    public record AdminSubmissionDetail(
            Long id,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            Integer questionTimeLimitSeconds,
            Integer questionsPerGame,
            QuizStatus status,
            QuizSource source,
            QuizModerationStatus moderationStatus,
            String moderationReason,
            Long ownerUserId,
            String ownerDisplayName,
            String ownerEmail,
            boolean ownerBanned,
            List<String> ownerRoles,
            List<LibraryQuestion> questions,
            Long submissionVersion
    ) {}

    public record OwnerModerationResult(
            Long userId,
            String displayName,
            String email,
            boolean banned,
            List<String> roles
    ) {}
}
