package pl.mindrush.backend.game.dto;

public record GamePlayerDto(
        String displayName,
        boolean answered,
        Boolean correct,
        long score,
        long correctAnswers,
        long totalAnswerTimeMs,
        long totalCorrectAnswerTimeMs,
        Integer xpDelta,
        Integer rankPointsDelta,
        Boolean winner
) {
}
