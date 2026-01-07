package pl.mindrush.backend.quiz.dto;

public record QuizListItemDto(
        Long id,
        String title,
        String category,
        long questionCount
) {
}

