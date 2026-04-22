package pl.mindrush.backend.shop;

import pl.mindrush.backend.AppUser;

import java.time.Instant;

public record ShopOrderEffectExecutionContext(
        AppUser user,
        ShopOrder order,
        Instant now
) {}
