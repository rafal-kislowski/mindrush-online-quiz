package pl.mindrush.backend.game.dto;

public record GamePlayerDto(
        String displayName,
        boolean answered,
        Boolean correct,
        long score
) {
}
