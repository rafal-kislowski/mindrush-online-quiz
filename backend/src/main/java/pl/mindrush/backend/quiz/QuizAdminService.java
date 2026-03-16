package pl.mindrush.backend.quiz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.media.MediaStorageService;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class QuizAdminService {

    private static final Logger log = LoggerFactory.getLogger(QuizAdminService.class);

    private final QuizRepository quizRepository;
    private final QuizCategoryRepository categoryRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAnswerOptionRepository optionRepository;
    private final MediaStorageService mediaStorageService;
    private final QuizUsageGuardService quizUsageGuardService;
    private final OpenAiQuizQuestionGenerationService questionGenerationService;
    private final OpenTdbQuizQuestionGenerationService openTdbQuestionGenerationService;
    private final QuizGenerationSourceFileService sourceFileService;

    private static final java.util.regex.Pattern HEX_COLOR =
            java.util.regex.Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    private static final int MIN_QUESTION_TIME_LIMIT_SECONDS = 5;
    private static final int MAX_QUESTION_TIME_LIMIT_SECONDS = 600;
    private static final int DEFAULT_QUESTION_TIME_LIMIT_SECONDS = Quiz.DEFAULT_QUESTION_TIME_LIMIT_SECONDS;
    private static final int MIN_QUESTIONS_PER_GAME = 1;
    private static final int MAX_QUESTIONS_PER_GAME = 10_000;
    private static final int DEFAULT_QUESTIONS_PER_GAME = Quiz.DEFAULT_QUESTIONS_PER_GAME;
    private static final int AI_GENERATION_DEFAULT_BATCH_SIZE = 20;
    private static final int AI_GENERATION_SOURCE_DEFAULT_BATCH_SIZE = 12;
    private static final int AI_GENERATION_MIN_BATCH_SIZE = 4;
    private static final int AI_GENERATION_MAX_CONSECUTIVE_TIMEOUTS = 4;
    private static final int AI_GENERATION_MAX_NO_PROGRESS_ROUNDS = 8;
    private static final int AI_GENERATION_MAX_ATTEMPTS_WITH_SOURCE = 36;
    private static final int AI_GENERATION_MAX_ATTEMPTS_NO_SOURCE = 48;
    private static final Duration AI_GENERATION_MAX_DURATION = Duration.ofMinutes(3);
    private static final int AI_SOURCE_CHUNK_TARGET_CHARS = 4_500;
    private static final int AI_SOURCE_CHUNK_MIN_SPLIT_CHARS = 2_200;
    private static final int AI_SOURCE_CHUNK_OVERLAP_CHARS = 200;

    public QuizAdminService(
            QuizRepository quizRepository,
            QuizCategoryRepository categoryRepository,
            QuizQuestionRepository questionRepository,
            QuizAnswerOptionRepository optionRepository,
            MediaStorageService mediaStorageService,
            QuizUsageGuardService quizUsageGuardService,
            OpenAiQuizQuestionGenerationService questionGenerationService,
            OpenTdbQuizQuestionGenerationService openTdbQuestionGenerationService,
            QuizGenerationSourceFileService sourceFileService
    ) {
        this.quizRepository = quizRepository;
        this.categoryRepository = categoryRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.mediaStorageService = mediaStorageService;
        this.quizUsageGuardService = quizUsageGuardService;
        this.questionGenerationService = questionGenerationService;
        this.openTdbQuestionGenerationService = openTdbQuestionGenerationService;
        this.sourceFileService = sourceFileService;
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
            Integer questionTimeLimitSeconds,
            Integer questionsPerGame
    ) {
        String t = title == null ? "" : title.trim();
        if (t.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "Title is required");

        QuizCategory category = null;
        if (categoryName != null && !categoryName.trim().isBlank()) {
            String name = categoryName.trim();
            category = categoryRepository.findByName(name).orElseGet(() -> categoryRepository.save(new QuizCategory(name)));
        }

        Quiz quiz = new Quiz(t, description == null ? null : description.trim(), category);
        quiz.setSource(QuizSource.OFFICIAL);
        applyAvatar(quiz, avatarImageUrl, avatarBgStart, avatarBgEnd, avatarTextColor);
        applyGameRules(quiz, gameMode, includeInRanking, xpEnabled, questionTimeLimitSeconds, questionsPerGame);
        return quizRepository.save(quiz);
    }

    @Transactional(readOnly = true)
    public List<AdminQuizListItem> listQuizzes() {
        return quizRepository.findAllWithCategory().stream()
                .filter(q -> q.getSource() == QuizSource.OFFICIAL)
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
                        q.getQuestionsPerGame(),
                        q.getStatus(),
                        questionRepository.countByQuizId(q.getId())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminQuizDetail getQuiz(Long quizId) {
        Quiz quiz = requireOfficialQuiz(quizId);

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
                quiz.getQuestionsPerGame(),
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
            Integer questionTimeLimitSeconds,
            Integer questionsPerGame
    ) {
        Quiz quiz = requireOfficialQuiz(quizId);

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
        applyGameRules(quiz, gameMode, includeInRanking, xpEnabled, questionTimeLimitSeconds, questionsPerGame);
        return quizRepository.save(quiz);
    }

    public QuizQuestion addQuestion(Long quizId, String prompt, String imageUrl, List<AnswerOptionInput> options) {
        Quiz quiz = requireOfficialQuiz(quizId);
        return addQuestionInternal(quiz, prompt, imageUrl, options);
    }

    private QuizQuestion addQuestionInternal(Quiz quiz, String prompt, String imageUrl, List<AnswerOptionInput> options) {
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

        int orderIndex = (int) questionRepository.countByQuizId(quiz.getId());
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

    public GeneratedQuestionsResult generateQuestions(Long quizId, QuestionGenerationInput rawInput) {
        return generateQuestionsWithAiSource(quizId, rawInput, null);
    }

    public GeneratedQuestionsResult generateQuestionsFromSourceFiles(
            Long quizId,
            QuestionGenerationInput rawInput,
            List<MultipartFile> sourceFiles
    ) {
        return generateQuestionsWithAiSource(quizId, rawInput, sourceFiles);
    }

    public GeneratedQuestionsResult generateQuestionsFromOpenTdb(Long quizId, OpenTdbQuestionGenerationInput rawInput) {
        Quiz quiz = requireOfficialQuiz(quizId);
        OpenTdbQuestionGenerationInput input = normalizeOpenTdbQuestionGenerationInput(rawInput);

        log.info(
                "Starting OpenTDB question generation (quizId={}, requestedCount={}, difficulty={}, categoryId={}, language={})",
                quizId,
                input.questionCount(),
                input.difficulty(),
                input.categoryId(),
                input.language()
        );

        List<String> disallowedPrompts = new java.util.ArrayList<>(loadExistingPrompts(quizId));
        List<OpenTdbQuizQuestionGenerationService.GeneratedQuestion> generated =
                openTdbQuestionGenerationService.generate(
                        new OpenTdbQuizQuestionGenerationService.QuestionGenerationPrompt(
                                input.questionCount(),
                                input.categoryId(),
                                input.difficulty(),
                                disallowedPrompts
                        )
                );
        if (input.language() == QuestionGenerationLanguage.PL) {
            generated = translateOpenTdbQuestionsToPolish(generated);
        }

        int generatedCount = persistGeneratedOpenTdbQuestions(quiz, generated);
        if (generatedCount <= 0) {
            throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB returned no usable questions");
        }
        if (generatedCount < input.questionCount()) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "OpenTDB could generate only "
                            + generatedCount
                            + " unique questions for selected filters. Requested "
                            + input.questionCount()
                            + "."
            );
        }

        long totalQuestions = questionRepository.countByQuizId(quizId);
        log.info(
                "OpenTDB question generation finished (quizId={}, requestedCount={}, generatedCount={}, totalQuestions={})",
                quizId,
                input.questionCount(),
                generatedCount,
                totalQuestions
        );
        return new GeneratedQuestionsResult(input.questionCount(), generatedCount, totalQuestions);
    }

    @Transactional(readOnly = true)
    public List<OpenTdbQuizQuestionGenerationService.OpenTdbCategory> listOpenTdbCategories() {
        return openTdbQuestionGenerationService.listCategories();
    }

    private GeneratedQuestionsResult generateQuestionsWithAiSource(
            Long quizId,
            QuestionGenerationInput rawInput,
            List<MultipartFile> sourceFiles
    ) {
        Quiz quiz = requireOfficialQuiz(quizId);
        QuestionGenerationInput input = normalizeQuestionGenerationInput(rawInput);
        String sourceMaterial = sourceFileService.extractSourceMaterial(sourceFiles);
        List<String> sourceMaterialChunks = splitSourceMaterialForGeneration(sourceMaterial);
        boolean hasSourceMaterial = !sourceMaterialChunks.isEmpty();

        log.info(
                "Starting AI question generation (quizId={}, requestedCount={}, difficulty={}, language={}, sourceFiles={})",
                quizId,
                input.questionCount(),
                input.difficulty(),
                input.language(),
                hasSourceMaterial ? sourceMaterialChunks.size() : 0
        );

        List<String> disallowedPrompts = new java.util.ArrayList<>(loadExistingPrompts(quizId));
        Set<String> seenPromptKeys = new java.util.HashSet<>(promptFingerprints(disallowedPrompts));

        List<OpenAiQuizQuestionGenerationService.GeneratedQuestion> generated = new java.util.ArrayList<>();
        int remaining = input.questionCount();
        int initialBatchSize = hasSourceMaterial
                ? AI_GENERATION_SOURCE_DEFAULT_BATCH_SIZE
                : AI_GENERATION_DEFAULT_BATCH_SIZE;
        int batchSize = Math.min(initialBatchSize, input.questionCount());
        int attempts = 0;
        int estimatedBatches = Math.max(1, (int) Math.ceil((double) input.questionCount() / Math.max(1, batchSize)));
        int maxAttemptsCap = hasSourceMaterial ? AI_GENERATION_MAX_ATTEMPTS_WITH_SOURCE : AI_GENERATION_MAX_ATTEMPTS_NO_SOURCE;
        int maxAttempts = Math.min(maxAttemptsCap, Math.max(10, estimatedBatches * 2));
        int sourceChunkCursor = 0;
        int consecutiveTimeouts = 0;
        int noProgressRounds = 0;
        long generationStartedAtNs = System.nanoTime();

        while (remaining > 0 && attempts < maxAttempts) {
            if (isAiGenerationDeadlineExceeded(generationStartedAtNs)) {
                log.warn(
                        "AI generation stopped due time limit (quizId={}, requestedCount={}, generatedCount={}, remaining={})",
                        quizId,
                        input.questionCount(),
                        generated.size(),
                        remaining
                );
                break;
            }
            attempts++;

            int currentBatchSize = Math.max(AI_GENERATION_MIN_BATCH_SIZE, Math.min(batchSize, remaining));
            String batchSourceMaterial = hasSourceMaterial
                    ? sourceMaterialChunks.get(Math.floorMod(sourceChunkCursor, sourceMaterialChunks.size()))
                    : null;
            List<OpenAiQuizQuestionGenerationService.GeneratedQuestion> batch;
            try {
                batch = questionGenerationService.generate(
                        new OpenAiQuizQuestionGenerationService.QuestionGenerationPrompt(
                                input.topic(),
                                input.categoryHint(),
                                input.instructions(),
                                batchSourceMaterial,
                                currentBatchSize,
                                input.difficulty(),
                                input.language(),
                                input.includeImages(),
                                quiz.getTitle(),
                                quiz.getCategory() == null ? null : quiz.getCategory().getName(),
                                quiz.getDescription(),
                                disallowedPrompts
                        )
                );
                consecutiveTimeouts = 0;
            } catch (ResponseStatusException ex) {
                if (isGatewayTimeout(ex)) {
                    consecutiveTimeouts++;
                    if (currentBatchSize > AI_GENERATION_MIN_BATCH_SIZE) {
                        int reducedBatchSize = Math.max(AI_GENERATION_MIN_BATCH_SIZE, currentBatchSize / 2);
                        if (reducedBatchSize < currentBatchSize) {
                            batchSize = reducedBatchSize;
                            log.warn(
                                    "AI generation batch timed out (quizId={}, requestedCount={}, remaining={}, retryBatchSize={})",
                                    quizId,
                                    input.questionCount(),
                                remaining,
                                batchSize
                        );
                        noProgressRounds++;
                        continue;
                    }
                }

                    if (hasSourceMaterial && sourceMaterialChunks.size() > 1 && consecutiveTimeouts < AI_GENERATION_MAX_CONSECUTIVE_TIMEOUTS) {
                        sourceChunkCursor++;
                        log.warn(
                                "AI generation timed out at min batch size; switching source chunk (quizId={}, requestedCount={}, remaining={}, timeoutStreak={})",
                                quizId,
                                input.questionCount(),
                                remaining,
                                consecutiveTimeouts
                        );
                        noProgressRounds++;
                        continue;
                    }

                    if (consecutiveTimeouts < AI_GENERATION_MAX_CONSECUTIVE_TIMEOUTS) {
                        log.warn(
                                "AI generation timed out at min batch size; retrying with same settings (quizId={}, requestedCount={}, remaining={}, timeoutStreak={})",
                                quizId,
                                input.questionCount(),
                                remaining,
                                consecutiveTimeouts
                        );
                        noProgressRounds++;
                        continue;
                    }
                }

                log.warn(
                        "AI question generation failed (quizId={}, requestedCount={}, remaining={}, status={}, reason={})",
                        quizId,
                        input.questionCount(),
                        remaining,
                        ex.getStatusCode().value(),
                        ex.getReason()
                );
                throw ex;
            }

            if (batch == null || batch.isEmpty()) {
                noProgressRounds++;
                if (hasSourceMaterial && sourceMaterialChunks.size() > 1) {
                    sourceChunkCursor++;
                }
                if (noProgressRounds >= AI_GENERATION_MAX_NO_PROGRESS_ROUNDS) {
                    log.warn(
                            "AI generation stopped due no progress (quizId={}, requestedCount={}, generatedCount={}, remaining={}, rounds={})",
                            quizId,
                            input.questionCount(),
                            generated.size(),
                            remaining,
                            noProgressRounds
                    );
                    break;
                }
                continue;
            }

            List<OpenAiQuizQuestionGenerationService.GeneratedQuestion> deduplicatedBatch = deduplicateAiBatch(batch, seenPromptKeys);
            if (deduplicatedBatch.isEmpty()) {
                noProgressRounds++;
                if (hasSourceMaterial && sourceMaterialChunks.size() > 1) {
                    sourceChunkCursor++;
                }
                if (currentBatchSize > AI_GENERATION_MIN_BATCH_SIZE) {
                    batchSize = Math.max(AI_GENERATION_MIN_BATCH_SIZE, currentBatchSize - 1);
                }
                if (noProgressRounds >= AI_GENERATION_MAX_NO_PROGRESS_ROUNDS) {
                    log.warn(
                            "AI generation stopped due duplicate/no-progress rounds (quizId={}, requestedCount={}, generatedCount={}, remaining={}, rounds={})",
                            quizId,
                            input.questionCount(),
                            generated.size(),
                            remaining,
                            noProgressRounds
                    );
                    break;
                }
                continue;
            }
            noProgressRounds = 0;

            generated.addAll(deduplicatedBatch);
            for (OpenAiQuizQuestionGenerationService.GeneratedQuestion generatedQuestion : deduplicatedBatch) {
                String prompt = trimToNull(generatedQuestion.prompt());
                if (prompt != null) {
                    disallowedPrompts.add(prompt);
                }
            }

            remaining -= deduplicatedBatch.size();
            if (remaining > 0 && deduplicatedBatch.size() < currentBatchSize && currentBatchSize > AI_GENERATION_MIN_BATCH_SIZE) {
                batchSize = Math.max(AI_GENERATION_MIN_BATCH_SIZE, currentBatchSize - 1);
            } else if (remaining > 0) {
                batchSize = Math.min(batchSize, remaining);
            }
            if (hasSourceMaterial && sourceMaterialChunks.size() > 1) {
                sourceChunkCursor++;
            }
        }

        int generatedCount = persistGeneratedAiQuestions(quiz, generated);
        if (generatedCount <= 0) {
            log.warn(
                    "AI returned no usable questions after validation (quizId={}, requestedCount={})",
                    quizId,
                    input.questionCount()
            );
            if (isAiGenerationDeadlineExceeded(generationStartedAtNs)) {
                throw new ResponseStatusException(GATEWAY_TIMEOUT, "AI generation reached the 3-minute limit and produced no usable questions");
            }
            throw new ResponseStatusException(BAD_GATEWAY, "AI provider returned no usable questions");
        }
        if (generatedCount < input.questionCount()) {
            log.warn(
                    "AI generated fewer unique questions than requested (quizId={}, requestedCount={}, generatedCount={})",
                    quizId,
                    input.questionCount(),
                    generatedCount
            );
        }

        long totalQuestions = questionRepository.countByQuizId(quizId);
        log.info(
                "AI question generation finished (quizId={}, requestedCount={}, generatedCount={}, totalQuestions={})",
                quizId,
                input.questionCount(),
                generatedCount,
                totalQuestions
        );
        return new GeneratedQuestionsResult(input.questionCount(), generatedCount, totalQuestions);
    }

    private int persistGeneratedAiQuestions(Quiz quiz, List<OpenAiQuizQuestionGenerationService.GeneratedQuestion> generated) {
        int generatedCount = 0;
        for (OpenAiQuizQuestionGenerationService.GeneratedQuestion generatedQuestion : generated) {
            List<OpenAiQuizQuestionGenerationService.GeneratedOption> generatedOptions =
                    generatedQuestion.options() == null ? List.of() : generatedQuestion.options();
            List<AnswerOptionInput> options = generatedOptions.stream()
                    .limit(4)
                    .map(option -> new AnswerOptionInput(option.text(), option.imageUrl(), false))
                    .toList();
            if (options.size() != 4) {
                continue;
            }

            List<AnswerOptionInput> normalizedOptions = buildNormalizedOptions(options, generatedQuestion.correctOptionIndex());
            if (normalizedOptions == null) {
                continue;
            }

            addQuestionInternal(
                    quiz,
                    generatedQuestion.prompt(),
                    generatedQuestion.imageUrl(),
                    normalizedOptions
            );
            generatedCount++;
        }
        return generatedCount;
    }

    private int persistGeneratedOpenTdbQuestions(Quiz quiz, List<OpenTdbQuizQuestionGenerationService.GeneratedQuestion> generated) {
        int generatedCount = 0;
        for (OpenTdbQuizQuestionGenerationService.GeneratedQuestion generatedQuestion : generated) {
            List<OpenTdbQuizQuestionGenerationService.GeneratedOption> generatedOptions =
                    generatedQuestion.options() == null ? List.of() : generatedQuestion.options();
            List<AnswerOptionInput> options = generatedOptions.stream()
                    .limit(4)
                    .map(option -> new AnswerOptionInput(option.text(), option.imageUrl(), false))
                    .toList();
            if (options.size() != 4) {
                continue;
            }

            List<AnswerOptionInput> normalizedOptions = buildNormalizedOptions(options, generatedQuestion.correctOptionIndex());
            if (normalizedOptions == null) {
                continue;
            }

            addQuestionInternal(
                    quiz,
                    generatedQuestion.prompt(),
                    generatedQuestion.imageUrl(),
                    normalizedOptions
            );
            generatedCount++;
        }
        return generatedCount;
    }

    private List<OpenTdbQuizQuestionGenerationService.GeneratedQuestion> translateOpenTdbQuestionsToPolish(
            List<OpenTdbQuizQuestionGenerationService.GeneratedQuestion> source
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        List<OpenAiQuizQuestionGenerationService.TranslationQuestion> translationInput = new java.util.ArrayList<>(source.size());
        for (OpenTdbQuizQuestionGenerationService.GeneratedQuestion question : source) {
            String prompt = trimToNull(question == null ? null : question.prompt());
            List<OpenTdbQuizQuestionGenerationService.GeneratedOption> options =
                    question == null || question.options() == null ? List.of() : question.options();
            if (prompt == null || options.size() < 4) {
                throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB produced invalid translation input");
            }

            List<String> optionTexts = new java.util.ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                String text = trimToNull(options.get(i) == null ? null : options.get(i).text());
                if (text == null) {
                    throw new ResponseStatusException(BAD_GATEWAY, "OpenTDB produced invalid translation option");
                }
                optionTexts.add(text);
            }

            translationInput.add(
                    new OpenAiQuizQuestionGenerationService.TranslationQuestion(
                            prompt,
                            optionTexts,
                            question.correctOptionIndex()
                    )
            );
        }

        List<OpenAiQuizQuestionGenerationService.TranslationQuestion> translated =
                questionGenerationService.translateQuestionsToPolish(translationInput);
        if (translated.size() != source.size()) {
            throw new ResponseStatusException(BAD_GATEWAY, "AI translation returned unexpected number of translated questions");
        }

        List<OpenTdbQuizQuestionGenerationService.GeneratedQuestion> result = new java.util.ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            OpenTdbQuizQuestionGenerationService.GeneratedQuestion original = source.get(i);
            OpenAiQuizQuestionGenerationService.TranslationQuestion translatedQuestion = translated.get(i);

            List<OpenTdbQuizQuestionGenerationService.GeneratedOption> translatedOptions = new java.util.ArrayList<>(4);
            for (int optIdx = 0; optIdx < 4; optIdx++) {
                translatedOptions.add(
                        new OpenTdbQuizQuestionGenerationService.GeneratedOption(
                                translatedQuestion.options().get(optIdx),
                                original.options().get(optIdx).imageUrl()
                        )
                );
            }

            result.add(
                    new OpenTdbQuizQuestionGenerationService.GeneratedQuestion(
                            translatedQuestion.prompt(),
                            original.imageUrl(),
                            translatedOptions,
                            original.correctOptionIndex()
                    )
            );
        }

        return result;
    }

    private static boolean isAiGenerationDeadlineExceeded(long generationStartedAtNs) {
        return (System.nanoTime() - generationStartedAtNs) >= AI_GENERATION_MAX_DURATION.toNanos();
    }

    private static List<String> splitSourceMaterialForGeneration(String sourceMaterial) {
        String normalized = trimToNull(sourceMaterial);
        if (normalized == null) {
            return List.of();
        }
        if (normalized.length() <= AI_SOURCE_CHUNK_TARGET_CHARS) {
            return List.of(normalized);
        }

        List<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        int length = normalized.length();
        while (start < length) {
            int hardEnd = Math.min(length, start + AI_SOURCE_CHUNK_TARGET_CHARS);
            int end = hardEnd;
            if (hardEnd < length) {
                int splitAt = normalized.lastIndexOf(' ', hardEnd);
                if (splitAt > start + AI_SOURCE_CHUNK_MIN_SPLIT_CHARS) {
                    end = splitAt;
                }
            }

            String chunk = trimToNull(normalized.substring(start, end));
            if (chunk != null) {
                chunks.add(chunk);
            }
            if (end >= length) {
                break;
            }

            int nextStart = Math.max(end - AI_SOURCE_CHUNK_OVERLAP_CHARS, start + 1);
            start = nextStart;
        }

        return chunks.isEmpty() ? List.of(normalized) : chunks;
    }

    private List<AnswerOptionInput> buildNormalizedOptions(List<AnswerOptionInput> options, int correctIndex) {
        if (options == null || options.size() != 4) {
            return null;
        }
        if (correctIndex < 0 || correctIndex > 3) {
            return null;
        }

        List<AnswerOptionInput> normalized = new java.util.ArrayList<>(4);
        for (int i = 0; i < options.size(); i++) {
            AnswerOptionInput option = options.get(i);
            normalized.add(new AnswerOptionInput(
                    option.text(),
                    option.imageUrl(),
                    i == correctIndex
            ));
        }
        return normalized;
    }

    private List<OpenAiQuizQuestionGenerationService.GeneratedQuestion> deduplicateAiBatch(
            List<OpenAiQuizQuestionGenerationService.GeneratedQuestion> batch,
            Set<String> seenPromptKeys
    ) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }
        List<OpenAiQuizQuestionGenerationService.GeneratedQuestion> result = new java.util.ArrayList<>(batch.size());
        for (OpenAiQuizQuestionGenerationService.GeneratedQuestion question : batch) {
            String key = promptFingerprint(question == null ? null : question.prompt());
            if (key == null || !seenPromptKeys.add(key)) {
                continue;
            }
            result.add(question);
        }
        return result;
    }

    public void updateQuestion(Long quizId, Long questionId, String prompt, String imageUrl, List<AnswerOptionUpdateInput> options) {
        requireOfficialQuiz(quizId);
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
        requireOfficialQuiz(quizId);
        QuizQuestion question = questionRepository.findByIdAndQuizId(questionId, quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Question not found"));
        optionRepository.deleteAllByQuestionId(questionId);
        questionRepository.delete(question);
    }

    public void deleteQuiz(Long quizId) {
        Quiz quiz = requireOfficialQuiz(quizId);
        if (quiz.getStatus() == QuizStatus.ACTIVE) {
            quizUsageGuardService.assertCanDeactivateOrDelete(quiz.getId(), "move this quiz to trash");
        }
        quiz.setStatus(QuizStatus.TRASHED);
        quizRepository.save(quiz);
    }

    public Quiz setStatus(Long quizId, QuizStatus status) {
        if (status == null) throw new ResponseStatusException(BAD_REQUEST, "Status is required");

        Quiz quiz = requireOfficialQuizWithCategory(quizId);
        if (quiz.getStatus() == QuizStatus.ACTIVE && status != QuizStatus.ACTIVE) {
            quizUsageGuardService.assertCanDeactivateOrDelete(quiz.getId(), "change quiz status");
        }

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
        Quiz quiz = requireOfficialQuiz(quizId);
        quizUsageGuardService.assertCanDeactivateOrDelete(quiz.getId(), "delete this quiz");

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

    public record QuestionGenerationInput(
            String topic,
            String categoryHint,
            String instructions,
            int questionCount,
            QuestionGenerationDifficulty difficulty,
            QuestionGenerationLanguage language,
            boolean includeImages
    ) {}

    public record OpenTdbQuestionGenerationInput(
            int questionCount,
            Integer categoryId,
            QuestionGenerationDifficulty difficulty,
            QuestionGenerationLanguage language
    ) {}

    public record GeneratedQuestionsResult(
            int requestedCount,
            int generatedCount,
            long totalQuestionCount
    ) {}

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
            Integer questionsPerGame,
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
            Integer questionsPerGame,
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

    private QuestionGenerationInput normalizeQuestionGenerationInput(QuestionGenerationInput input) {
        if (input == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Generation settings are required");
        }

        String topic = trimToNull(input.topic());
        if (topic == null) {
            topic = "General knowledge";
        }

        String categoryHint = trimToNull(input.categoryHint());
        String instructions = trimToNull(input.instructions());
        int questionCount = input.questionCount() <= 0 ? 1 : input.questionCount();
        questionCount = Math.min(questionCount, questionGenerationService.maxQuestionCount());
        QuestionGenerationDifficulty difficulty = input.difficulty() == null
                ? QuestionGenerationDifficulty.MIXED
                : input.difficulty();
        QuestionGenerationLanguage language = input.language() == null
                ? QuestionGenerationLanguage.PL
                : input.language();

        return new QuestionGenerationInput(
                topic,
                categoryHint,
                instructions,
                questionCount,
                difficulty,
                language,
                false
        );
    }

    private OpenTdbQuestionGenerationInput normalizeOpenTdbQuestionGenerationInput(OpenTdbQuestionGenerationInput input) {
        if (input == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Generation settings are required");
        }

        int questionCount = input.questionCount() <= 0 ? 1 : input.questionCount();
        questionCount = Math.min(questionCount, openTdbQuestionGenerationService.maxQuestionCount());

        Integer categoryId = input.categoryId();
        if (categoryId != null && categoryId <= 0) {
            categoryId = null;
        }

        QuestionGenerationDifficulty difficulty = input.difficulty() == null
                ? QuestionGenerationDifficulty.MIXED
                : input.difficulty();
        QuestionGenerationLanguage language = input.language() == null
                ? QuestionGenerationLanguage.EN
                : input.language();

        return new OpenTdbQuestionGenerationInput(questionCount, categoryId, difficulty, language);
    }

    private List<String> loadExistingPrompts(Long quizId) {
        return questionRepository.findAllByQuizIdOrderByOrderIndexAsc(quizId).stream()
                .map(QuizQuestion::getPrompt)
                .map(QuizAdminService::trimToNull)
                .filter(prompt -> prompt != null)
                .toList();
    }

    private static Set<String> promptFingerprints(List<String> prompts) {
        if (prompts == null || prompts.isEmpty()) {
            return Set.of();
        }
        return prompts.stream()
                .map(QuizAdminService::promptFingerprint)
                .filter(key -> key != null)
                .collect(Collectors.toSet());
    }

    private static String promptFingerprint(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        String reduced = normalized
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[^\\p{L}\\p{N} ]", "")
                .trim();
        return reduced.isBlank() ? null : reduced;
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isBlank() ? null : t;
    }

    private static boolean isGatewayTimeout(ResponseStatusException ex) {
        return ex != null && ex.getStatusCode().value() == 504;
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
            Integer questionTimeLimitSeconds,
            Integer questionsPerGame
    ) {
        GameMode mode = gameMode == null ? GameMode.CASUAL : gameMode;
        boolean ranking = Boolean.TRUE.equals(includeInRanking);

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

        int normalizedQuestionsPerGame = questionsPerGame == null || questionsPerGame <= 0
                ? DEFAULT_QUESTIONS_PER_GAME
                : questionsPerGame;
        if (normalizedQuestionsPerGame < MIN_QUESTIONS_PER_GAME || normalizedQuestionsPerGame > MAX_QUESTIONS_PER_GAME) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Questions per game must be between "
                            + MIN_QUESTIONS_PER_GAME
                            + " and "
                            + MAX_QUESTIONS_PER_GAME
            );
        }

        quiz.setGameMode(mode);
        quiz.setIncludeInRanking(ranking);
        quiz.setXpEnabled(xp);
        quiz.setQuestionTimeLimitSeconds(normalizedLimit);
        quiz.setQuestionsPerGame(normalizedQuestionsPerGame);
    }

    private Quiz requireOfficialQuiz(Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        if (quiz.getSource() != QuizSource.OFFICIAL) {
            throw new ResponseStatusException(NOT_FOUND, "Quiz not found");
        }
        return quiz;
    }

    private Quiz requireOfficialQuizWithCategory(Long quizId) {
        Quiz quiz = quizRepository.findByIdWithCategory(quizId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Quiz not found"));
        if (quiz.getSource() != QuizSource.OFFICIAL) {
            throw new ResponseStatusException(NOT_FOUND, "Quiz not found");
        }
        return quiz;
    }
}
