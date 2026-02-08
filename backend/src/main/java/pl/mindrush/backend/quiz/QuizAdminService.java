package pl.mindrush.backend.quiz;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.media.MediaStorageService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class QuizAdminService {

    private final QuizRepository quizRepository;
    private final QuizCategoryRepository categoryRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAnswerOptionRepository optionRepository;
    private final MediaStorageService mediaStorageService;

    private static final java.util.regex.Pattern HEX_COLOR =
            java.util.regex.Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    private static final int MIN_QUESTION_TIME_LIMIT_SECONDS = 5;
    private static final int MAX_QUESTION_TIME_LIMIT_SECONDS = 600;
    private static final int DEFAULT_QUESTION_TIME_LIMIT_SECONDS = Quiz.DEFAULT_QUESTION_TIME_LIMIT_SECONDS;

    public QuizAdminService(
            QuizRepository quizRepository,
            QuizCategoryRepository categoryRepository,
            QuizQuestionRepository questionRepository,
            QuizAnswerOptionRepository optionRepository,
            MediaStorageService mediaStorageService
    ) {
        this.quizRepository = quizRepository;
        this.categoryRepository = categoryRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.mediaStorageService = mediaStorageService;
    }

    public Quiz createQuiz(
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            GameMode gameMode,
            Boolean includeInRanking,
            Boolean xpEnabled,
            Integer questionTimeLimitSeconds
    ) {
        String t = title == null ? "" : title.trim();
        if (t.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "Title is required");

        QuizCategory category = null;
        if (categoryName != null && !categoryName.trim().isBlank()) {
            String name = categoryName.trim();
            category = categoryRepository.findByName(name).orElseGet(() -> categoryRepository.save(new QuizCategory(name)));
        }

        Quiz quiz = new Quiz(t, description == null ? null : description.trim(), category);
        applyAvatar(quiz, avatarImageUrl, avatarBgStart, avatarBgEnd, avatarTextColor);
        applyGameRules(quiz, gameMode, includeInRanking, xpEnabled, questionTimeLimitSeconds);
        return quizRepository.save(quiz);
    }

    @Transactional(readOnly = true)
    public List<AdminQuizListItem> listQuizzes() {
        return quizRepository.findAllWithCategory().stream()
                .map(q -> new AdminQuizListItem(
                        q.getId(),
                        q.getTitle(),
                        q.getDescription(),
                        q.getCategory() == null ? null : q.getCategory().getName(),
                        q.getAvatarImageUrl(),
                        q.getAvatarBgStart(),
                        q.getAvatarBgEnd(),
                        q.getAvatarTextColor(),
                        q.getGameMode(),
                        q.isIncludeInRanking(),
                        q.isXpEnabled(),
                        q.getQuestionTimeLimitSeconds(),
                        q.getStatus(),
                        questionRepository.countByQuizId(q.getId())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminQuizDetail getQuiz(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));

        List<QuizQuestion> questions = questionRepository.findAllByQuizIdOrderByOrderIndexAsc(quizId);
        List<Long> questionIds = questions.stream().map(QuizQuestion::getId).toList();

        Map<Long, List<AdminAnswerOption>> optionsByQuestionId = new HashMap<>();
        if (!questionIds.isEmpty()) {
            optionRepository.findAllByQuestionIdInOrderByQuestionIdAscOrderIndexAsc(questionIds).forEach(o -> {
                optionsByQuestionId.computeIfAbsent(o.getQuestion().getId(), ignored -> new java.util.ArrayList<>())
                        .add(new AdminAnswerOption(o.getId(), o.getOrderIndex(), o.getText(), o.getImageUrl(), o.isCorrect()));
            });
        }

        List<AdminQuestion> qDtos = questions.stream()
                .map(q -> new AdminQuestion(
                        q.getId(),
                        q.getOrderIndex(),
                        q.getPrompt(),
                        q.getImageUrl(),
                        optionsByQuestionId.getOrDefault(q.getId(), List.of())
                ))
                .toList();

        return new AdminQuizDetail(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getCategory() == null ? null : quiz.getCategory().getName(),
                quiz.getAvatarImageUrl(),
                quiz.getAvatarBgStart(),
                quiz.getAvatarBgEnd(),
                quiz.getAvatarTextColor(),
                quiz.getGameMode(),
                quiz.isIncludeInRanking(),
                quiz.isXpEnabled(),
                quiz.getQuestionTimeLimitSeconds(),
                quiz.getStatus(),
                qDtos
        );
    }

    public Quiz updateQuiz(
            Long quizId,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            GameMode gameMode,
            Boolean includeInRanking,
            Boolean xpEnabled,
            Integer questionTimeLimitSeconds
    ) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));

        String t = title == null ? "" : title.trim();
        if (t.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "Title is required");

        QuizCategory category = null;
        if (categoryName != null && !categoryName.trim().isBlank()) {
            String name = categoryName.trim();
            category = categoryRepository.findByName(name).orElseGet(() -> categoryRepository.save(new QuizCategory(name)));
        }

        quiz.setTitle(t);
        quiz.setDescription(description == null ? null : description.trim());
        quiz.setCategory(category);
        applyAvatar(quiz, avatarImageUrl, avatarBgStart, avatarBgEnd, avatarTextColor);
        applyGameRules(quiz, gameMode, includeInRanking, xpEnabled, questionTimeLimitSeconds);
        return quizRepository.save(quiz);
    }

    public QuizQuestion addQuestion(Long quizId, String prompt, String imageUrl, List<AnswerOptionInput> options) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));

        String p = prompt == null ? "" : prompt.trim();
        if (p.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "Prompt is required");

        if (options == null || options.size() != 4) {
            throw new ResponseStatusException(BAD_REQUEST, "Exactly 4 answer options are required");
        }

        long correctCount = options.stream().filter(o -> o != null && o.correct()).count();
        if (correctCount != 1) {
            throw new ResponseStatusException(BAD_REQUEST, "Exactly 1 answer option must be correct");
        }
        for (AnswerOptionInput o : options) {
            String text = o == null ? "" : (o.text() == null ? "" : o.text().trim());
            String img = o == null ? "" : (o.imageUrl() == null ? "" : o.imageUrl().trim());
            if (text.isBlank() && img.isBlank()) {
                throw new ResponseStatusException(BAD_REQUEST, "Each answer option must have text or an image");
            }
        }

        int orderIndex = (int) questionRepository.countByQuizId(quizId);
        QuizQuestion q = questionRepository.save(new QuizQuestion(quiz, p, orderIndex));
        q.setImageUrl(trimToNull(imageUrl));

        for (int i = 0; i < options.size(); i++) {
            AnswerOptionInput in = options.get(i);
            String text = in.text() == null ? "" : in.text().trim();
            QuizAnswerOption opt = new QuizAnswerOption(q, text, in.correct(), i);
            opt.setImageUrl(trimToNull(in.imageUrl()));
            optionRepository.save(opt);
        }

        return q;
    }

    public void updateQuestion(Long quizId, Long questionId, String prompt, String imageUrl, List<AnswerOptionUpdateInput> options) {
        QuizQuestion question = questionRepository.findByIdAndQuizId(questionId, quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Question not found"));

        String p = prompt == null ? "" : prompt.trim();
        if (p.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "Prompt is required");

        if (options == null || options.size() != 4) {
            throw new ResponseStatusException(BAD_REQUEST, "Exactly 4 answer options are required");
        }

        long correctCount = options.stream().filter(o -> o != null && o.correct()).count();
        if (correctCount != 1) {
            throw new ResponseStatusException(BAD_REQUEST, "Exactly 1 answer option must be correct");
        }
        for (AnswerOptionUpdateInput o : options) {
            String text = o == null ? "" : (o.text() == null ? "" : o.text().trim());
            String img = o == null ? "" : (o.imageUrl() == null ? "" : o.imageUrl().trim());
            if (text.isBlank() && img.isBlank()) {
                throw new ResponseStatusException(BAD_REQUEST, "Each answer option must have text or an image");
            }
            if (o.id() == null) throw new ResponseStatusException(BAD_REQUEST, "Answer option id is required");
        }

        question.setPrompt(p);
        question.setImageUrl(trimToNull(imageUrl));

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
            AnswerOptionUpdateInput in = options.get(i);
            QuizAnswerOption opt = existingById.get(in.id());
            opt.setText(in.text() == null ? "" : in.text().trim());
            opt.setCorrect(in.correct());
            opt.setOrderIndex(i);
            opt.setImageUrl(trimToNull(in.imageUrl()));
            optionRepository.save(opt);
        }
        questionRepository.save(question);
    }

    public void deleteQuestion(Long quizId, Long questionId) {
        QuizQuestion question = questionRepository.findByIdAndQuizId(questionId, quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Question not found"));
        optionRepository.deleteAllByQuestionId(questionId);
        questionRepository.delete(question);
    }

    public void deleteQuiz(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        quiz.setStatus(QuizStatus.TRASHED);
        quizRepository.save(quiz);
    }

    public Quiz setStatus(Long quizId, QuizStatus status) {
        if (status == null) throw new ResponseStatusException(BAD_REQUEST, "Status is required");

        Quiz quiz = quizRepository.findByIdWithCategory(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));

        if (status == QuizStatus.ACTIVE) {
            long questionCount = questionRepository.countByQuizId(quizId);
            if (questionCount <= 0) {
                throw new ResponseStatusException(BAD_REQUEST, "Quiz must have at least 1 question to publish");
            }
        }

        quiz.setStatus(status);
        return quizRepository.save(quiz);
    }

    public void purgeQuiz(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));

        List<QuizQuestion> questions = questionRepository.findAllByQuizIdOrderByOrderIndexAsc(quizId);
        List<Long> qIds = questions.stream().map(QuizQuestion::getId).toList();

        Set<String> mediaUrls = new java.util.HashSet<>();
        if (quiz.getAvatarImageUrl() != null) mediaUrls.add(quiz.getAvatarImageUrl());
        for (QuizQuestion q : questions) {
            if (q.getImageUrl() != null) mediaUrls.add(q.getImageUrl());
        }
        if (!qIds.isEmpty()) {
            optionRepository.findAllByQuestionIdInOrderByQuestionIdAscOrderIndexAsc(qIds).forEach(o -> {
                if (o.getImageUrl() != null) mediaUrls.add(o.getImageUrl());
            });
        }

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

    public record AnswerOptionInput(String text, String imageUrl, boolean correct) {}

    public record AnswerOptionUpdateInput(Long id, String text, String imageUrl, boolean correct) {}

    public record AdminQuizListItem(
            Long id,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            GameMode gameMode,
            boolean includeInRanking,
            boolean xpEnabled,
            Integer questionTimeLimitSeconds,
            QuizStatus status,
            long questionCount
    ) {}

    public record AdminQuizDetail(
            Long id,
            String title,
            String description,
            String categoryName,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor,
            GameMode gameMode,
            boolean includeInRanking,
            boolean xpEnabled,
            Integer questionTimeLimitSeconds,
            QuizStatus status,
            List<AdminQuestion> questions
    ) {}

    public record AdminQuestion(
            Long id,
            int orderIndex,
            String prompt,
            String imageUrl,
            List<AdminAnswerOption> options
    ) {}

    public record AdminAnswerOption(
            Long id,
            int orderIndex,
            String text,
            String imageUrl,
            boolean correct
    ) {}

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isBlank() ? null : t;
    }

    private static String trimToNullColor(String v) {
        String t = trimToNull(v);
        if (t == null) return null;
        if (!HEX_COLOR.matcher(t).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid color: " + t);
        }
        return t;
    }

    private static void applyAvatar(
            Quiz quiz,
            String avatarImageUrl,
            String avatarBgStart,
            String avatarBgEnd,
            String avatarTextColor
    ) {
        quiz.setAvatarImageUrl(trimToNull(avatarImageUrl));
        quiz.setAvatarBgStart(trimToNullColor(avatarBgStart));
        quiz.setAvatarBgEnd(trimToNullColor(avatarBgEnd));
        quiz.setAvatarTextColor(trimToNullColor(avatarTextColor));
    }

    private static void applyGameRules(
            Quiz quiz,
            GameMode gameMode,
            Boolean includeInRanking,
            Boolean xpEnabled,
            Integer questionTimeLimitSeconds
    ) {
        GameMode mode = gameMode == null ? GameMode.CASUAL : gameMode;
        boolean ranking = Boolean.TRUE.equals(includeInRanking);
        if (mode == GameMode.RANKED) ranking = true;

        boolean xp = xpEnabled == null || xpEnabled;

        int normalizedLimit = questionTimeLimitSeconds == null || questionTimeLimitSeconds <= 0
                ? DEFAULT_QUESTION_TIME_LIMIT_SECONDS
                : questionTimeLimitSeconds;
        if (normalizedLimit < MIN_QUESTION_TIME_LIMIT_SECONDS || normalizedLimit > MAX_QUESTION_TIME_LIMIT_SECONDS) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Question time limit must be between "
                            + MIN_QUESTION_TIME_LIMIT_SECONDS
                            + " and "
                            + MAX_QUESTION_TIME_LIMIT_SECONDS
                            + " seconds"
            );
        }

        quiz.setGameMode(mode);
        quiz.setIncludeInRanking(ranking);
        quiz.setXpEnabled(xp);
        quiz.setQuestionTimeLimitSeconds(normalizedLimit);
    }
}
