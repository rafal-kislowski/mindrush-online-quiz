package pl.mindrush.backend.quiz;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    public QuizAdminService(
            QuizRepository quizRepository,
            QuizCategoryRepository categoryRepository,
            QuizQuestionRepository questionRepository,
            QuizAnswerOptionRepository optionRepository
    ) {
        this.quizRepository = quizRepository;
        this.categoryRepository = categoryRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
    }

    public Quiz createQuiz(String title, String description, String categoryName) {
        String t = title == null ? "" : title.trim();
        if (t.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "Title is required");

        QuizCategory category = null;
        if (categoryName != null && !categoryName.trim().isBlank()) {
            String name = categoryName.trim();
            category = categoryRepository.findByName(name).orElseGet(() -> categoryRepository.save(new QuizCategory(name)));
        }

        Quiz quiz = new Quiz(t, description == null ? null : description.trim(), category);
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
                qDtos
        );
    }

    public Quiz updateQuiz(Long quizId, String title, String description, String categoryName) {
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
        List<QuizQuestion> questions = questionRepository.findAllByQuizIdOrderByOrderIndexAsc(quizId);
        List<Long> qIds = questions.stream().map(QuizQuestion::getId).toList();
        if (!qIds.isEmpty()) optionRepository.deleteAllByQuestionIdIn(qIds);
        questionRepository.deleteAll(questions);
        quizRepository.delete(quiz);
    }

    public record AnswerOptionInput(String text, String imageUrl, boolean correct) {}

    public record AnswerOptionUpdateInput(Long id, String text, String imageUrl, boolean correct) {}

    public record AdminQuizListItem(
            Long id,
            String title,
            String description,
            String categoryName,
            long questionCount
    ) {}

    public record AdminQuizDetail(
            Long id,
            String title,
            String description,
            String categoryName,
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
}
