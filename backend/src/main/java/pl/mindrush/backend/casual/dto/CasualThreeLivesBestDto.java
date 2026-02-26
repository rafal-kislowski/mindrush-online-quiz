package pl.mindrush.backend.casual.dto;

public record CasualThreeLivesBestDto(
        int points,
        int answered,
        long durationMs,
        String updatedAt
) {
}

