package pl.mindrush.backend.shop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.AppUserRepository;
import pl.mindrush.backend.config.AppShopProperties;
import pl.mindrush.backend.mail.ShopMailWorkflowService;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
@Transactional
public class ShopCheckoutService {

    private final Clock clock;
    private final AppUserRepository userRepository;
    private final ShopOrderRepository orderRepository;
    private final AppShopProperties shopProperties;
    private final ShopCatalogService catalogService;
    private final PremiumAccessService premiumAccessService;
    private final ShopOrderFulfillmentService fulfillmentService;
    private final ShopMailWorkflowService shopMailWorkflowService;

    public ShopCheckoutService(
            Clock clock,
            AppUserRepository userRepository,
            ShopOrderRepository orderRepository,
            AppShopProperties shopProperties,
            ShopCatalogService catalogService,
            PremiumAccessService premiumAccessService,
            ShopOrderFulfillmentService fulfillmentService,
            ShopMailWorkflowService shopMailWorkflowService
    ) {
        this.clock = clock;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.shopProperties = shopProperties;
        this.catalogService = catalogService;
        this.premiumAccessService = premiumAccessService;
        this.fulfillmentService = fulfillmentService;
        this.shopMailWorkflowService = shopMailWorkflowService;
    }

    public List<OrderView> listOrders(Long userId) {
        return orderRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toView)
                .toList();
    }

    public OrderView createOrder(Long userId, String productSlug, String planCode, int quantity) {
        List<OrderView> created = createOrders(userId, List.of(new CreateOrderLineInput(productSlug, planCode, quantity)));
        if (created.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Order payload is empty");
        }
        return created.get(0);
    }

    public List<OrderView> createOrders(Long userId, List<CreateOrderLineInput> items) {
        List<CreateOrderLineInput> safeItems = items == null ? List.of() : items;
        if (safeItems.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "At least one order item is required");
        }
        if (safeItems.size() > 100) {
            throw new ResponseStatusException(BAD_REQUEST, "Too many order items");
        }

        AppUser user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        premiumAccessService.synchronizeUser(user);
        ShopPaymentProvider provider = requireSupportedProvider();

        List<ShopOrder> createdOrders = new ArrayList<>(safeItems.size());
        for (CreateOrderLineInput line : safeItems) {
            int safeQuantity = validateQuantity(line.quantity());
            ShopOrder order = buildOrder(user, line.productSlug(), line.planCode(), safeQuantity, provider);
            createdOrders.add(orderRepository.save(order));
        }

        shopMailWorkflowService.sendOrderCreated(user, createdOrders);
        return createdOrders.stream().map(this::toView).toList();
    }

    private ShopOrder buildOrder(
            AppUser user,
            String productSlug,
            String planCode,
            int safeQuantity,
            ShopPaymentProvider provider
    ) {
        ShopProduct product = catalogService.requireCheckoutProduct(productSlug);
        ShopProductPlan plan = catalogService.requirePlan(product, planCode);
        Instant now = clock.instant();
        ShopOrder order = new ShopOrder();
        order.setPublicId(UUID.randomUUID().toString());
        order.setUserId(user.getId());
        order.setProduct(product);
        order.setProductCodeSnapshot(product.getCode());
        order.setProductSlugSnapshot(product.getSlug());
        order.setProductNameSnapshot(product.getTitle());
        order.setPlanCode(plan.getCode());
        order.setPlanNameSnapshot(plan.getLabel());
        order.setCurrency(plan.getCurrency());
        order.setQuantity(safeQuantity);
        order.setGrossAmountMinor(safeMultiply(plan.getGrossAmountMinor(), safeQuantity));
        order.setDurationDays(safeMultiply(plan.getDurationDays(), safeQuantity));
        order.setPaymentProvider(provider);
        order.setPaymentStatus(ShopPaymentStatus.PENDING);
        order.setCustomerEmail(String.valueOf(user.getEmail() == null ? "" : user.getEmail()).trim());
        order.setCustomerDisplayName(resolveDisplayName(user));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setPaidAt(null);
        order.setFulfilledAt(null);
        order.setPremiumStartsAt(null);
        order.setPremiumExpiresAt(null);
        order.setPaymentReference(null);
        order.setProviderOrderId(null);
        order.setCheckoutRedirectUrl(null);
        order.setFailureReason(null);
        snapshotOrderEffects(order, plan);
        return order;
    }

    private ShopPaymentProvider requireSupportedProvider() {
        ShopPaymentProvider provider = shopProperties.getPaymentProvider();
        if (provider == ShopPaymentProvider.PAYU_SANDBOX) {
            throw new ResponseStatusException(
                    SERVICE_UNAVAILABLE,
                    "PayU sandbox gateway is configured as next integration step but is not enabled in this build yet"
            );
        }
        return provider;
    }

    private static int validateQuantity(Integer quantity) {
        int safeQuantity = quantity == null ? 1 : quantity;
        if (safeQuantity < 1) {
            throw new ResponseStatusException(BAD_REQUEST, "Quantity must be at least 1");
        }
        if (safeQuantity > 1000) {
            throw new ResponseStatusException(BAD_REQUEST, "Quantity is too high");
        }
        return safeQuantity;
    }

    public OrderView simulatePayment(Long userId, String publicId, SimulationOutcome outcome) {
        ShopOrder order = orderRepository.findByPublicIdAndUserIdForUpdate(publicId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Order not found"));
        if (order.getPaymentProvider() != ShopPaymentProvider.SIMULATED) {
            throw new ResponseStatusException(CONFLICT, "Simulation is available only for the simulated payment provider");
        }

        SimulationOutcome safeOutcome = outcome == null ? SimulationOutcome.SUCCESS : outcome;
        if (isIdempotentMatch(order, safeOutcome)) {
            if (safeOutcome == SimulationOutcome.SUCCESS && order.getFulfilledAt() == null) {
                fulfillmentService.fulfillPaidOrder(order, clock.instant());
                orderRepository.save(order);
            }
            return toView(order);
        }
        if (order.getPaymentStatus() != ShopPaymentStatus.PENDING) {
            throw new ResponseStatusException(CONFLICT, "This order can no longer change payment state");
        }

        Instant now = clock.instant();
        if (safeOutcome == SimulationOutcome.SUCCESS) {
            if (isCoinsCurrency(order.getCurrency())) {
                AppUser user = userRepository.findByIdForUpdate(order.getUserId())
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
                premiumAccessService.synchronizeUser(user);
                int totalCoinsCost = Math.max(0, order.getGrossAmountMinor());
                if (user.getCoins() < totalCoinsCost) {
                    throw new ResponseStatusException(BAD_REQUEST, "Not enough coins for this purchase");
                }
                user.setCoins(user.getCoins() - totalCoinsCost);
            }
            order.setPaymentStatus(ShopPaymentStatus.PAID);
            order.setPaidAt(now);
            order.setUpdatedAt(now);
            order.setPaymentReference("SIM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT));
            fulfillmentService.fulfillPaidOrder(order, now);
            ShopOrder saved = orderRepository.save(order);
            return toView(saved);
        }

        order.setPaymentStatus(safeOutcome == SimulationOutcome.CANCEL ? ShopPaymentStatus.CANCELLED : ShopPaymentStatus.FAILED);
        order.setUpdatedAt(now);
        order.setFailureReason(safeOutcome == SimulationOutcome.CANCEL
                ? "Payment was cancelled in simulated checkout"
                : "Payment was declined in simulated checkout");
        ShopOrder saved = orderRepository.save(order);
        return toView(saved);
    }

    private static void snapshotOrderEffects(ShopOrder order, ShopProductPlan plan) {
        if (order == null || plan == null) return;
        order.getEffects().clear();
        int quantityFactor = Math.max(1, order.getQuantity());
        List<ShopProductPlanEffect> planEffects = plan.getEffects() == null ? List.of() : plan.getEffects();
        for (int i = 0; i < planEffects.size(); i++) {
            ShopProductPlanEffect source = planEffects.get(i);
            ShopOrderEffect snapshot = new ShopOrderEffect();
            snapshot.setOrder(order);
            snapshot.setEffectType(source.getEffectType());
            snapshot.setEffectCode(source.getEffectCode());
            snapshot.setEffectValue(safeMultiply(source.getEffectValue(), quantityFactor));
            snapshot.setSortOrder(i);
            snapshot.setAppliedAt(null);
            order.getEffects().add(snapshot);
        }
        if (order.getEffects().isEmpty() && plan.getDurationDays() > 0) {
            ShopOrderEffect durationFallback = new ShopOrderEffect();
            durationFallback.setOrder(order);
            durationFallback.setEffectType(ShopProductPlanEffectType.DURATION_DAYS);
            durationFallback.setEffectCode("PREMIUM_ACCESS");
            durationFallback.setEffectValue(safeMultiply(plan.getDurationDays(), quantityFactor));
            durationFallback.setSortOrder(0);
            durationFallback.setAppliedAt(null);
            order.getEffects().add(durationFallback);
        }
    }

    private boolean isIdempotentMatch(ShopOrder order, SimulationOutcome outcome) {
        if (outcome == SimulationOutcome.SUCCESS) {
            return order.getPaymentStatus() == ShopPaymentStatus.PAID;
        }
        if (outcome == SimulationOutcome.FAILURE) {
            return order.getPaymentStatus() == ShopPaymentStatus.FAILED;
        }
        return order.getPaymentStatus() == ShopPaymentStatus.CANCELLED;
    }

    private OrderView toView(ShopOrder order) {
        return new OrderView(
                order.getPublicId(),
                order.getProductCodeSnapshot(),
                order.getProductSlugSnapshot(),
                order.getProductNameSnapshot(),
                order.getPlanCode(),
                order.getPlanNameSnapshot(),
                order.getCurrency(),
                order.getGrossAmountMinor(),
                order.getQuantity(),
                order.getDurationDays(),
                order.getPaymentProvider().name(),
                order.getPaymentStatus().name(),
                order.getCheckoutRedirectUrl(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.getFulfilledAt(),
                order.getPremiumStartsAt(),
                order.getPremiumExpiresAt(),
                order.getPaymentProvider() == ShopPaymentProvider.SIMULATED && order.getPaymentStatus() == ShopPaymentStatus.PENDING
        );
    }

    private static String resolveDisplayName(AppUser user) {
        String displayName = String.valueOf(user.getDisplayName() == null ? "" : user.getDisplayName()).trim();
        if (!displayName.isBlank()) {
            return displayName;
        }
        String email = String.valueOf(user.getEmail() == null ? "" : user.getEmail()).trim();
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : "Player";
    }

    private static int safeMultiply(int value, int factor) {
        if (value <= 0 || factor <= 0) return 0;
        long total = (long) value * (long) factor;
        if (total > Integer.MAX_VALUE) {
            throw new ResponseStatusException(BAD_REQUEST, "Quantity is too high for the selected plan");
        }
        return (int) total;
    }

    private static boolean isCoinsCurrency(String currency) {
        return "COINS".equalsIgnoreCase(String.valueOf(currency == null ? "" : currency).trim());
    }

    public enum SimulationOutcome {
        SUCCESS,
        FAILURE,
        CANCEL
    }

    public record OrderView(
            String publicId,
            String productCode,
            String productSlug,
            String productName,
            String planCode,
            String planName,
            String currency,
            int grossAmountMinor,
            int quantity,
            int durationDays,
            String paymentProvider,
            String paymentStatus,
            String checkoutRedirectUrl,
            String failureReason,
            Instant createdAt,
            Instant paidAt,
            Instant fulfilledAt,
            Instant premiumStartsAt,
            Instant premiumExpiresAt,
            boolean simulationActionsEnabled
    ) {}

    public record CreateOrderLineInput(
            String productSlug,
            String planCode,
            Integer quantity
    ) {}
}
