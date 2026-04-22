package pl.mindrush.backend.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PremiumExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PremiumExpirationScheduler.class);

    private final PremiumAccessService premiumAccessService;

    public PremiumExpirationScheduler(
            PremiumAccessService premiumAccessService
    ) {
        this.premiumAccessService = premiumAccessService;
    }

    @Scheduled(fixedDelayString = "${app.shop.premium-expiration-check-ms:60000}")
    public void expirePremiumAccess() {
        int expiredCount = premiumAccessService.expireExpiredUsers();
        if (expiredCount > 0) {
            log.info("Expired {} premium subscription(s)", expiredCount);
        }
    }
}
