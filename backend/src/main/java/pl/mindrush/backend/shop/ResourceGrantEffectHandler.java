package pl.mindrush.backend.shop;

import org.springframework.stereotype.Component;
import pl.mindrush.backend.AppUser;

@Component
public class ResourceGrantEffectHandler implements ShopOrderEffectHandler {

    @Override
    public boolean supports(ShopProductPlanEffectType type, String code) {
        if (type != ShopProductPlanEffectType.RESOURCE_GRANT) return false;
        String normalized = String.valueOf(code == null ? "" : code).trim().toUpperCase();
        return "COINS".equals(normalized) || "XP".equals(normalized) || "RANK_POINTS".equals(normalized);
    }

    @Override
    public void apply(ShopOrderEffectExecutionContext context, ShopOrderEffect effect) {
        if (context == null || context.user() == null || effect == null) return;
        int value = Math.max(0, effect.getEffectValue());
        if (value <= 0) return;

        AppUser user = context.user();
        String code = String.valueOf(effect.getEffectCode() == null ? "" : effect.getEffectCode()).trim().toUpperCase();
        if ("COINS".equals(code)) {
            user.setCoins(safeAdd(user.getCoins(), value));
            return;
        }
        if ("XP".equals(code)) {
            user.setXp(safeAdd(user.getXp(), value));
            return;
        }
        if ("RANK_POINTS".equals(code)) {
            user.setRankPoints(safeAdd(user.getRankPoints(), value));
        }
    }

    private static int safeAdd(int base, int delta) {
        long total = (long) base + (long) delta;
        if (total < 0L) return 0;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
}
