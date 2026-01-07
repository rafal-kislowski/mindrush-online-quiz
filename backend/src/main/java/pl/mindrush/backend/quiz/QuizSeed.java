package pl.mindrush.backend.quiz;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class QuizSeed implements CommandLineRunner {

    private final QuizCategoryRepository categoryRepository;
    private final QuizRepository quizRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAnswerOptionRepository optionRepository;

    public QuizSeed(
            QuizCategoryRepository categoryRepository,
            QuizRepository quizRepository,
            QuizQuestionRepository questionRepository,
            QuizAnswerOptionRepository optionRepository
    ) {
        this.categoryRepository = categoryRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!quizRepository.findAll().isEmpty()) {
            return;
        }

        QuizCategory general = categoryRepository.findByName("General Knowledge")
                .orElseGet(() -> categoryRepository.save(new QuizCategory("General Knowledge")));

        Quiz quiz = quizRepository.save(new Quiz(
                "MindRush Starter Quiz",
                "A small starter quiz for local development.",
                general
        ));

        QuizQuestion q1 = questionRepository.save(new QuizQuestion(quiz, "Which planet is known as the Red Planet?", 1));
        optionRepository.save(new QuizAnswerOption(q1, "Mars", true, 1));
        optionRepository.save(new QuizAnswerOption(q1, "Venus", false, 2));
        optionRepository.save(new QuizAnswerOption(q1, "Jupiter", false, 3));
        optionRepository.save(new QuizAnswerOption(q1, "Mercury", false, 4));

        QuizQuestion q2 = questionRepository.save(new QuizQuestion(quiz, "What does HTTP stand for?", 2));
        optionRepository.save(new QuizAnswerOption(q2, "HyperText Transfer Protocol", true, 1));
        optionRepository.save(new QuizAnswerOption(q2, "High Transfer Text Process", false, 2));
        optionRepository.save(new QuizAnswerOption(q2, "Hyper Terminal Transport Program", false, 3));
        optionRepository.save(new QuizAnswerOption(q2, "Host Transfer Tool Package", false, 4));
    }
}

