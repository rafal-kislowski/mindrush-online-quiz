package pl.mindrush.backend.shop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.AppRole;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.mail.ShopMailWorkflowService;
import pl.mindrush.backend.notification.UserNotificationService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class PremiumAccessService {

    private final Clock clock;
    private final AppUserRepository userRepository;
    private final UserNotificationService notificationService;
    private final ShopMailWorkflowService shopMailWorkflowService;

    public PremiumAccessService(
            Clock clock,
            AppUserRepository userRepository,
            UserNotificationService notificationService,
            ShopMailWorkflowService shopMailWorkflowService
    ) {
        this.clock = clock;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.shopMailWorkflowService = shopMailWorkflowService;
    }

    public AppUser synchronizeUser(AppUser user) {
        if (user == null) return null;
        return synchronizeUser(user, clock.instant());
    }

    public PremiumGrantResult grantPremium(Long userId, Duration duration, Instant grantedAt) {
        if (userId == null) {
            throw new ResponseStatusException(NOT_FOUND, "User not found");
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Premium duration must be positive");
        }

        Instant now = grantedAt == null ? clock.instant() : grantedAt;
        AppUser user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        synchronizeUser(user, now);

        Instant currentExpiry = user.getPremiumExpiresAt();
        boolean activeNow = currentExpiry != null && currentExpiry.isAfter(now);
        Instant effectiveStart = activeNow ? currentExpiry : now;
        Instant effectiveEnd = effectiveStart.plus(duration);

        Set<AppRole> roles = new HashSet<>(user.getRoles());
        roles.add(AppRole.PREMIUM);
        user.setRoles(roles);
        if (!activeNow || user.getPremiumActivatedAt() == null) {
            user.setPremiumActivatedAt(now);
        }
        user.setPremiumExpiresAt(effectiveEnd);
        userRepository.save(user);
        return new PremiumGrantResult(user, effectiveStart, effectiveEnd);
    }

    public int expireExpiredUsers() {
        Instant now = clock.instant();
        List<Long> expiredUserIds = userRepository.findAllIdsByRoleAndPremiumExpiresAtLessThanEqual(AppRole.PREMIUM, now);
        int changed = 0;
        for (Long userId : expiredUserIds) {
            AppUser user = userRepository.findByIdForUpdate(userId).orElse(null);
            if (user == null) continue;
            if (synchronizeUser(user, now).getRoles().contains(AppRole.PREMIUM)) {
                continue;
            }
            changed++;
        }
        return changed;
    }

    public boolean isPremiumActive(AppUser user) {
        return isPremiumActive(user, clock.instant());
    }

    private boolean isPremiumActive(AppUser user, Instant now) {
        if (user == null) return false;
        Instant expiresAt = user.getPremiumExpiresAt();
        return expiresAt != null && expiresAt.isAfter(now);
    }

    private AppUser synchronizeUser(AppUser user, Instant now) {
        boolean hasPremiumRole = user.getRoles().contains(AppRole.PREMIUM);
        boolean active = isPremiumActive(user, now);
        if (active && hasPremiumRole) {
            return user;
        }
        if (active) {
            Set<AppRole> roles = new HashSet<>(user.getRoles());
            roles.add(AppRole.PREMIUM);
            user.setRoles(roles);
            return userRepository.save(user);
        }
        if (!hasPremiumRole) {
            return user;
        }

        Set<AppRole> roles = new HashSet<>(user.getRoles());
        roles.remove(AppRole.PREMIUM);
        user.setRoles(roles);
        AppUser saved = userRepository.save(user);
        notificationService.createPremiumExpiredNotification(saved.getId(), saved.getPremiumExpiresAt());
        shopMailWorkflowService.sendPremiumExpired(saved, saved.getPremiumExpiresAt());
        return saved;
    }

    public record PremiumGrantResult(
            AppUser user,
            Instant effectiveStart,
            Instant effectiveEnd
    ) {}
}
