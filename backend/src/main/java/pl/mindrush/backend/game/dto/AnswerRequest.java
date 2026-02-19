package pl.mindrush.backend.game.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AnswerRequest(
        @NotNull(message = "questionId is required")
        @Positive(message = "questionId must be a positive number")
        Long questionId,
        @NotNull(message = "optionId is required")
        @Positive(message = "optionId must be a positive number")
        Long optionId
) {
}
