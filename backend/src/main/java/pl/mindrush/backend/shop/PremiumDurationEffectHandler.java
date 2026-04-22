package pl.mindrush.backend.shop;

import org.springframework.stereotype.Component;
import pl.mindrush.backend.notification.UserNotificationService;
import pl.mindrush.backend.mail.ShopMailWorkflowService;

import java.time.Duration;

@Component
public class PremiumDurationEffectHandler implements ShopOrderEffectHandler {

    private final PremiumAccessService premiumAccessService;
    private final UserNotificationService notificationService;
    private final ShopMailWorkflowService shopMailWorkflowService;

    public PremiumDurationEffectHandler(
            PremiumAccessService premiumAccessService,
            UserNotificationService notificationService,
            ShopMailWorkflowService shopMailWorkflowService
    ) {
        this.premiumAccessService = premiumAccessService;
        this.notificationService = notificationService;
        this.shopMailWorkflowService = shopMailWorkflowService;
    }

    @Override
    public boolean supports(ShopProductPlanEffectType type, String code) {
        return type == ShopProductPlanEffectType.DURATION_DAYS && "PREMIUM_ACCESS".equalsIgnoreCase(String.valueOf(code));
    }

    @Override
    public void apply(ShopOrderEffectExecutionContext context, ShopOrderEffect effect) {
        if (context == null || effect == null || context.user() == null || context.order() == null) return;
        int days = Math.max(0, effect.getEffectValue());
        if (days <= 0) return;
        PremiumAccessService.PremiumGrantResult granted = premiumAccessService.grantPremium(
                context.user().getId(),
                Duration.ofDays(days),
                context.now()
        );
        context.order().setPremiumStartsAt(granted.effectiveStart());
        context.order().setPremiumExpiresAt(granted.effectiveEnd());
        boolean extended = granted.effectiveStart() != null
                && context.now() != null
                && granted.effectiveStart().isAfter(context.now());
        notificationService.createPremiumActivatedNotification(context.user().getId(), granted.effectiveEnd(), extended);
        shopMailWorkflowService.sendPremiumActivated(granted.user(), context.order(), granted.effectiveEnd(), extended, days);
    }
}
