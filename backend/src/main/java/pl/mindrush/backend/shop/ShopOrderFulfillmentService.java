package pl.mindrush.backend.shop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class ShopOrderFulfillmentService {

    private final Clock clock;
    private final AppUserRepository userRepository;
    private final ShopOrderEffectHandlerRegistry handlerRegistry;

    public ShopOrderFulfillmentService(
            Clock clock,
            AppUserRepository userRepository,
            ShopOrderEffectHandlerRegistry handlerRegistry
    ) {
        this.clock = clock;
        this.userRepository = userRepository;
        this.handlerRegistry = handlerRegistry;
    }

    public void fulfillPaidOrder(ShopOrder order, Instant fulfilledAt) {
        if (order == null) return;
        if (order.getPaymentStatus() != ShopPaymentStatus.PAID) {
            throw new ResponseStatusException(BAD_REQUEST, "Only paid orders can be fulfilled");
        }
        if (order.getFulfilledAt() != null) {
            return;
        }

        Instant now = fulfilledAt == null ? clock.instant() : fulfilledAt;
        AppUser user = userRepository.findByIdForUpdate(order.getUserId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));

        List<ShopOrderEffect> effects = ensureOrderEffectSnapshots(order);
        if (effects.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Order has no fulfillment effects");
        }

        ShopOrderEffectExecutionContext context = new ShopOrderEffectExecutionContext(user, order, now);
        for (ShopOrderEffect effect : effects) {
            if (effect.getAppliedAt() != null) {
                continue;
            }
            String code = String.valueOf(effect.getEffectCode() == null ? "" : effect.getEffectCode())
                    .trim()
                    .toUpperCase(Locale.ROOT);
            ShopOrderEffectHandler handler = handlerRegistry.resolve(effect.getEffectType(), code);
            handler.apply(context, effect);
            effect.setAppliedAt(now);
        }

        order.setFulfilledAt(now);
        order.setUpdatedAt(now);
    }

    private static List<ShopOrderEffect> ensureOrderEffectSnapshots(ShopOrder order) {
        if (order.getEffects() != null && !order.getEffects().isEmpty()) {
            return order.getEffects().stream()
                    .sorted(Comparator.comparingInt(ShopOrderEffect::getSortOrder).thenComparing(ShopOrderEffect::getId, Comparator.nullsLast(Long::compareTo)))
                    .toList();
        }

        ShopProductPlan plan = order.getProduct().getPlans().stream()
                .filter(item -> String.valueOf(item.getCode()).equalsIgnoreCase(String.valueOf(order.getPlanCode())))
                .findFirst()
                .orElse(null);
        if (plan == null) {
            return List.of();
        }

        List<ShopOrderEffect> snapshots = new ArrayList<>();
        List<ShopProductPlanEffect> planEffects = plan.getEffects() == null ? List.of() : plan.getEffects();
        for (int i = 0; i < planEffects.size(); i++) {
            ShopProductPlanEffect source = planEffects.get(i);
            ShopOrderEffect snapshot = new ShopOrderEffect();
            snapshot.setOrder(order);
            snapshot.setEffectType(source.getEffectType());
            snapshot.setEffectCode(source.getEffectCode());
            snapshot.setEffectValue(source.getEffectValue());
            snapshot.setSortOrder(i);
            snapshots.add(snapshot);
        }

        if (snapshots.isEmpty() && order.getDurationDays() > 0) {
            ShopOrderEffect durationFallback = new ShopOrderEffect();
            durationFallback.setOrder(order);
            durationFallback.setEffectType(ShopProductPlanEffectType.DURATION_DAYS);
            durationFallback.setEffectCode("PREMIUM_ACCESS");
            durationFallback.setEffectValue(order.getDurationDays());
            durationFallback.setSortOrder(0);
            snapshots.add(durationFallback);
        }

        order.getEffects().addAll(snapshots);
        return snapshots;
    }
}
