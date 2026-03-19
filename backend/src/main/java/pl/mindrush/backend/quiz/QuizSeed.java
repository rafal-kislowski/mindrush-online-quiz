package pl.mindrush.backend.quiz;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class QuizSeed implements CommandLineRunner {

    private final StarterQuizSeeder starterQuizSeeder;

    public QuizSeed(StarterQuizSeeder starterQuizSeeder) {
        this.starterQuizSeeder = starterQuizSeeder;
    }

    @Override
    public void run(String... args) {
        starterQuizSeeder.seedIfEmpty();
    }
}
