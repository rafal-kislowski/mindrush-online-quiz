package pl.mindrush.backend.quiz;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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

    public QuizQuestion addQuestion(Long quizId, String prompt, List<AnswerOptionInput> options) {
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
            if (text.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "Answer option text is required");
        }

        int orderIndex = (int) questionRepository.countByQuizId(quizId);
        QuizQuestion q = questionRepository.save(new QuizQuestion(quiz, p, orderIndex));

        for (int i = 0; i < options.size(); i++) {
            AnswerOptionInput in = options.get(i);
            optionRepository.save(new QuizAnswerOption(q, in.text().trim(), in.correct(), i));
        }

        return q;
    }

    public record AnswerOptionInput(String text, boolean correct) {}
}

