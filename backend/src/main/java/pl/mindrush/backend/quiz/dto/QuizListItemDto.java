package pl.mindrush.backend.quiz.dto;

import pl.mindrush.backend.quiz.GameMode;

public record QuizListItemDto(
        Long id,
        String title,
        String description,
        String categoryName,
        String source,
        String avatarImageUrl,
        String avatarBgStart,
        String avatarBgEnd,
        String avatarTextColor,
        GameMode gameMode,
        boolean includeInRanking,
        boolean xpEnabled,
        Integer questionTimeLimitSeconds,
        Integer questionsPerGame,
        long questionCount
) {
}
