package pl.mindrush.backend.quiz.dto;

import java.util.List;

public record QuizQuestionDto(
        Long id,
        String prompt,
        String imageUrl,
        List<QuizAnswerOptionDto> options
) {
}
