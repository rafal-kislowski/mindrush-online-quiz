package pl.mindrush.backend.game.dto;

import java.util.List;

public record GameStateDto(
        String lobbyCode,
        String lobbyStatus,
        String gameStatus,
        String mode,
        int questionIndex,
        int totalQuestions,
        String stage,
        String serverTime,
        String stageEndsAt,
        Long stageTotalMs,
        GameQuestionDto question,
        List<GamePlayerDto> players,
        String gameSessionId,
        Long correctOptionId,
        Integer livesRemaining,
        Integer wrongAnswers,
        String finishReason
) {
}
