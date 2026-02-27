package pl.mindrush.backend.game.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import pl.mindrush.backend.game.GameSessionMode;

public record StartGameRequest(
        @NotNull(message = "quizId is required")
        @Positive(message = "quizId must be a positive number") Long quizId,
        GameSessionMode mode,
        Boolean ranked
) {
}
