package pl.mindrush.backend.shop;

import org.springframework.stereotype.Component;
import pl.mindrush.backend.AppUser;

import java.time.Duration;
import java.time.Instant;

@Component
public class RewardBoostDurationEffectHandler implements ShopOrderEffectHandler {

    @Override
    public boolean supports(ShopProductPlanEffectType type, String code) {
        if (type != ShopProductPlanEffectType.DURATION_DAYS) return false;
        String normalized = String.valueOf(code == null ? "" : code).trim().toUpperCase();
        return "XP_BOOST".equals(normalized) || "RP_BOOST".equals(normalized) || "COINS_BOOST".equals(normalized);
    }

    @Override
    public void apply(ShopOrderEffectExecutionContext context, ShopOrderEffect effect) {
        if (context == null || context.user() == null || effect == null) return;
        int days = Math.max(0, effect.getEffectValue());
        if (days <= 0) return;

        AppUser user = context.user();
        Instant now = context.now();
        String code = String.valueOf(effect.getEffectCode() == null ? "" : effect.getEffectCode()).trim().toUpperCase();

        if ("XP_BOOST".equals(code)) {
            user.setXpBoostExpiresAt(stackUntil(user.getXpBoostExpiresAt(), now, days));
            return;
        }
        if ("RP_BOOST".equals(code)) {
            user.setRankPointsBoostExpiresAt(stackUntil(user.getRankPointsBoostExpiresAt(), now, days));
            return;
        }
        if ("COINS_BOOST".equals(code)) {
            user.setCoinsBoostExpiresAt(stackUntil(user.getCoinsBoostExpiresAt(), now, days));
        }
    }

    private static Instant stackUntil(Instant current, Instant now, int days) {
        Instant base = current != null && current.isAfter(now) ? current : now;
        return base.plus(Duration.ofDays(Math.max(0, days)));
    }
}
