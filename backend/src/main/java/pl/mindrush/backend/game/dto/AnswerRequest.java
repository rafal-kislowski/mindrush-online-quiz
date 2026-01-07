package pl.mindrush.backend.game.dto;

public record AnswerRequest(
        Long questionId,
        Long optionId
) {
}

