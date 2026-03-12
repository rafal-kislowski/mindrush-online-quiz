package pl.mindrush.backend.game.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GamePlayerDto(
        String displayName,
        @JsonProperty("isAuthenticated")
        boolean isAuthenticated,
        @JsonProperty("isPremium")
        boolean isPremium,
        boolean answered,
        Boolean correct,
        long score,
        long correctAnswers,
        long totalAnswerTimeMs,
        long totalCorrectAnswerTimeMs,
        Integer xpDelta,
        Integer coinsDelta,
        Integer rankPointsDelta,
        Integer rankPoints,
        Boolean winner
) {
}
