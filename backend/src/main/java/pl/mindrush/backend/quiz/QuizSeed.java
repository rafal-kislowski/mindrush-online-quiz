package pl.mindrush.backend.quiz;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class QuizSeed implements CommandLineRunner {

    private static final int QUIZ_COUNT = 12;
    private static final int QUESTIONS_PER_QUIZ = 5;

    private static final String[][] QUIZ_BLUEPRINTS = new String[][]{
            {"MindRush Starter Quiz 01", "General warm-up questions for UI testing.", "General Knowledge"},
            {"MindRush Starter Quiz 02", "Science basics mixed with trivia.", "Science"},
            {"MindRush Starter Quiz 03", "Countries, capitals and world facts.", "Geography"},
            {"MindRush Starter Quiz 04", "Popular history checkpoints and events.", "History"},
            {"MindRush Starter Quiz 05", "Tech and internet fundamentals.", "Technology"},
            {"MindRush Starter Quiz 06", "Sports and competition trivia.", "Sports"},
            {"MindRush Starter Quiz 07", "Entertainment and pop culture mix.", "Entertainment"},
            {"MindRush Starter Quiz 08", "Nature and environment basics.", "Nature"},
            {"MindRush Starter Quiz 09", "Everyday logic and common facts.", "General Knowledge"},
            {"MindRush Starter Quiz 10", "Space, physics and discoveries.", "Science"},
            {"MindRush Starter Quiz 11", "Maps, regions and landmarks.", "Geography"},
            {"MindRush Starter Quiz 12", "Timeline highlights and inventions.", "History"}
    };

    private static final QuestionTemplate[] QUESTION_POOL = new QuestionTemplate[]{
            new QuestionTemplate(
                    "Which planet is known as the Red Planet?",
                    new String[]{"Mars", "Venus", "Jupiter", "Mercury"},
                    0
            ),
            new QuestionTemplate(
                    "What does HTTP stand for?",
                    new String[]{"HyperText Transfer Protocol", "High Transfer Text Process", "Hyper Terminal Transport Program", "Host Transfer Tool Package"},
                    0
            ),
            new QuestionTemplate(
                    "What is the largest ocean on Earth?",
                    new String[]{"Pacific Ocean", "Atlantic Ocean", "Indian Ocean", "Arctic Ocean"},
                    0
            ),
            new QuestionTemplate(
                    "Which language is primarily used for Android development today?",
                    new String[]{"Kotlin", "Swift", "Ruby", "Go"},
                    0
            ),
            new QuestionTemplate(
                    "How many continents are there?",
                    new String[]{"7", "5", "6", "8"},
                    0
            ),
            new QuestionTemplate(
                    "Which country hosted the 2016 Summer Olympics?",
                    new String[]{"Brazil", "China", "United Kingdom", "Japan"},
                    0
            ),
            new QuestionTemplate(
                    "What is H2O commonly called?",
                    new String[]{"Water", "Oxygen", "Hydrogen", "Salt"},
                    0
            ),
            new QuestionTemplate(
                    "Who wrote 'Romeo and Juliet'?",
                    new String[]{"William Shakespeare", "Charles Dickens", "Mark Twain", "Jane Austen"},
                    0
            ),
            new QuestionTemplate(
                    "Which instrument has keys, pedals and strings?",
                    new String[]{"Piano", "Guitar", "Violin", "Drum"},
                    0
            ),
            new QuestionTemplate(
                    "Which number is a prime number?",
                    new String[]{"13", "12", "15", "21"},
                    0
            ),
            new QuestionTemplate(
                    "What is the capital city of Canada?",
                    new String[]{"Ottawa", "Toronto", "Vancouver", "Montreal"},
                    0
            ),
            new QuestionTemplate(
                    "Which gas do plants absorb from the atmosphere?",
                    new String[]{"Carbon dioxide", "Nitrogen", "Oxygen", "Helium"},
                    0
            ),
            new QuestionTemplate(
                    "In computing, what does CPU stand for?",
                    new String[]{"Central Processing Unit", "Computer Primary Utility", "Central Program Upload", "Core Processing Usage"},
                    0
            ),
            new QuestionTemplate(
                    "Which famous wall fell in 1989?",
                    new String[]{"Berlin Wall", "Great Wall of China", "Hadrian's Wall", "Wailing Wall"},
                    0
            ),
            new QuestionTemplate(
                    "How many players are on a standard soccer team on the field?",
                    new String[]{"11", "9", "10", "12"},
                    0
            ),
            new QuestionTemplate(
                    "Which element has chemical symbol 'Na'?",
                    new String[]{"Sodium", "Nitrogen", "Neon", "Nickel"},
                    0
            )
    };

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

        for (int quizIndex = 0; quizIndex < QUIZ_COUNT; quizIndex++) {
            String[] blueprint = QUIZ_BLUEPRINTS[quizIndex % QUIZ_BLUEPRINTS.length];
            QuizCategory category = getOrCreateCategory(blueprint[2]);

            Quiz quiz = new Quiz(
                    blueprint[0],
                    blueprint[1],
                    category
            );
            quiz.setStatus(QuizStatus.ACTIVE);
            quiz.setQuestionTimeLimitSeconds(15 + (quizIndex % 3) * 5);
            if (quizIndex % 4 == 3) {
                quiz.setGameMode(GameMode.RANKED);
                quiz.setIncludeInRanking(true);
            }
            quiz = quizRepository.save(quiz);

            seedQuestionsForQuiz(quiz, quizIndex);
        }
    }

    private void seedQuestionsForQuiz(Quiz quiz, int quizIndex) {
        int baseOffset = quizIndex * QUESTIONS_PER_QUIZ;
        for (int questionIndex = 0; questionIndex < QUESTIONS_PER_QUIZ; questionIndex++) {
            QuestionTemplate template = QUESTION_POOL[(baseOffset + questionIndex) % QUESTION_POOL.length];
            QuizQuestion question = questionRepository.save(
                    new QuizQuestion(quiz, template.prompt(), questionIndex + 1)
            );

            for (int optionIndex = 0; optionIndex < template.options().length; optionIndex++) {
                optionRepository.save(new QuizAnswerOption(
                        question,
                        template.options()[optionIndex],
                        optionIndex == template.correctOptionIndex(),
                        optionIndex + 1
                ));
            }
        }
    }

    private QuizCategory getOrCreateCategory(String categoryName) {
        return categoryRepository.findByName(categoryName)
                .orElseGet(() -> categoryRepository.save(new QuizCategory(categoryName)));
    }

    private record QuestionTemplate(
            String prompt,
            String[] options,
            int correctOptionIndex
    ) {
    }
}
