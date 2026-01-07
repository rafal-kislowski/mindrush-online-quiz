package pl.mindrush.backend.game.dto;

import java.util.List;

public record GameQuestionDto(
        Long id,
        String prompt,
        List<GameOptionDto> options
) {
}

