package pl.mindrush.backend.quiz.dto;

public record QuizDetailDto(
        Long id,
        String title,
        String description,
        String category,
        long questionCount
) {
}

