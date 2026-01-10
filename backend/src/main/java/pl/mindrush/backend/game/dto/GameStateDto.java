package pl.mindrush.backend.game.dto;

import java.util.List;

public record GameStateDto(
        String lobbyCode,
        String lobbyStatus,
        String gameStatus,
        int questionIndex,
        int totalQuestions,
        String stage,
        String stageEndsAt,
        GameQuestionDto question,
        List<GamePlayerDto> players,
        String gameSessionId,
        Long correctOptionId
) {
}
