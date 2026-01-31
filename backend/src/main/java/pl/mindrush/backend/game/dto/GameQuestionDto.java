package pl.mindrush.backend.game.dto;

import java.util.List;

public record GameQuestionDto(
        Long id,
        String prompt,
        String imageUrl,
        List<GameOptionDto> options
) {
}
