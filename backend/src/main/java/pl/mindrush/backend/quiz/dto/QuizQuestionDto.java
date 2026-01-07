package pl.mindrush.backend.quiz.dto;

import java.util.List;

public record QuizQuestionDto(
        Long id,
        String prompt,
        List<QuizAnswerOptionDto> options
) {
}

