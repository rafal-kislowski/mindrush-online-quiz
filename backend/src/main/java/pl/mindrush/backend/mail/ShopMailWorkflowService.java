package pl.mindrush.backend.mail;

import org.springframework.stereotype.Service;
import pl.mindrush.backend.AppUser;
import pl.mindrush.backend.config.AppMailProperties;
import pl.mindrush.backend.shop.ShopOrder;
import pl.mindrush.backend.shop.ShopPaymentProvider;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ShopMailWorkflowService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC);

    private final TransactionalMailService mailService;
    private final AppMailProperties mailProperties;

    public ShopMailWorkflowService(
            TransactionalMailService mailService,
            AppMailProperties mailProperties
    ) {
        this.mailService = mailService;
        this.mailProperties = mailProperties;
    }

    public void sendOrderCreated(AppUser user, ShopOrder order) {
        if (order == null) return;
        sendOrderCreated(user, List.of(order));
    }

    public void sendOrderCreated(AppUser user, List<ShopOrder> orders) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;
        List<ShopOrder> safeOrders = orders == null ? List.of() : orders.stream().filter(o -> o != null).toList();
        if (safeOrders.isEmpty()) return;

        List<OrderLineMailView> lines = buildLines(safeOrders);
        List<OrderTotalMailView> totals = buildTotals(safeOrders);
        boolean coinsOnly = safeOrders.stream().allMatch(order -> isCoinsCurrency(order.getCurrency()));
        boolean simulatedProvider = safeOrders.stream().anyMatch(order -> order.getPaymentProvider() == ShopPaymentProvider.SIMULATED);

        ShopOrder first = safeOrders.get(0);
        String orderReference = safeOrders.size() == 1
                ? safe(first.getPublicId())
                : safe(first.getPublicId()) + " +" + (safeOrders.size() - 1);

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("displayName", displayNameFor(user));
        variables.put("orderReference", orderReference);
        variables.put("itemsCount", lines.size());
        variables.put("lineItems", lines);
        variables.put("totals", totals);
        variables.put("showProvider", !coinsOnly);
        variables.put("paymentProvider", safe(first.getPaymentProvider() == null ? null : first.getPaymentProvider().name()));
        variables.put("showSimulationInfo", !coinsOnly && simulatedProvider);
        variables.put("supportEmail", safe(mailProperties.getSupportEmail()));
        variables.put("actionUrl", buildShopUrl(safeOrders.size() == 1
                ? "/shop/" + safe(first.getProductSlugSnapshot())
                : "/shop/cart"));
        variables.put("actionLabel", safeOrders.size() == 1 ? "Open product page" : "Open cart");

        mailService.sendTemplate(
                user.getEmail(),
                "MindRush - order received",
                "mail/shop-order-created",
                variables
        );
    }

    public void sendPremiumActivated(
            AppUser user,
            ShopOrder order,
            Instant premiumEndsAt,
            boolean extended,
            int addedDays
    ) {
        if (user == null || order == null || user.getEmail() == null || user.getEmail().isBlank()) return;
        int safeAddedDays = Math.max(0, addedDays > 0 ? addedDays : Math.max(0, order.getDurationDays()));
        String eventTitle = extended ? "Premium extended" : "Premium activated";
        String introText = extended
                ? "your premium order has been paid and your active premium was extended by %s.".formatted(formatDurationDays(safeAddedDays))
                : "your premium order has been paid and activated. Premium time has been added to your account immediately.";
        String addedTimeLabel = extended ? "Extended by" : "Activated time";
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("displayName", displayNameFor(user));
        variables.put("premiumEventTitle", eventTitle);
        variables.put("premiumIntroText", introText);
        variables.put("orderId", safe(order.getPublicId()));
        variables.put("productName", safe(order.getProductNameSnapshot()));
        variables.put("planName", safe(order.getPlanNameSnapshot()));
        variables.put("addedTimeLabel", addedTimeLabel);
        variables.put("addedTimeValue", formatDurationDays(safeAddedDays));
        variables.put("premiumEndsAt", formatInstant(premiumEndsAt));
        variables.put("supportEmail", safe(mailProperties.getSupportEmail()));
        variables.put("actionUrl", buildShopUrl("/shop/premium"));

        mailService.sendTemplate(
                user.getEmail(),
                "MindRush - " + (extended ? "premium extended" : "premium activated"),
                "mail/premium-activated",
                variables
        );
    }

    public void sendPremiumExpired(AppUser user, Instant expiredAt) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;

        mailService.sendTemplate(
                user.getEmail(),
                "MindRush - premium expired",
                "mail/premium-expired",
                Map.of(
                        "displayName", displayNameFor(user),
                        "premiumExpiredAt", formatInstant(expiredAt),
                        "supportEmail", safe(mailProperties.getSupportEmail()),
                        "actionUrl", buildShopUrl("/shop/premium")
                )
        );
    }

    private String buildShopUrl(String path) {
        String base = normalizeBaseUrl(safe(mailProperties.getFrontendBaseUrl()));
        String normalizedPath = path == null || path.isBlank()
                ? "/"
                : (path.startsWith("/") ? path : "/" + path);
        return trimTrailingSlash(base) + normalizedPath;
    }

    private static String formatMinorAmount(int amountMinor, String currency) {
        if (isCoinsCurrency(currency)) {
            NumberFormat coinsFormat = NumberFormat.getIntegerInstance(Locale.US);
            return coinsFormat.format(Math.max(0, amountMinor)) + " " + safe(currency);
        }
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(Math.max(0, amountMinor) / 100.0d) + " " + safe(currency);
    }

    private static List<OrderLineMailView> buildLines(List<ShopOrder> orders) {
        Map<String, AggregatedOrderLine> grouped = new LinkedHashMap<>();
        for (ShopOrder order : orders) {
            String productName = safe(order.getProductNameSnapshot());
            String planName = safe(order.getPlanNameSnapshot());
            String currency = safe(order.getCurrency()).toUpperCase(Locale.ROOT);
            int quantity = Math.max(1, order.getQuantity());
            int lineTotalMinor = Math.max(0, order.getGrossAmountMinor());
            int unitPriceMinor = quantity == 0 ? lineTotalMinor : (lineTotalMinor / quantity);
            String key = productName + "|" + planName + "|" + currency + "|" + unitPriceMinor;
            grouped.computeIfAbsent(key, ignored -> new AggregatedOrderLine(productName, planName, currency, unitPriceMinor))
                    .addQuantity(quantity);
            grouped.get(key).addLineTotal(lineTotalMinor);
        }

        List<OrderLineMailView> result = new ArrayList<>(grouped.size());
        for (AggregatedOrderLine line : grouped.values()) {
            result.add(new OrderLineMailView(
                    line.productName,
                    line.planName,
                    line.quantity,
                    formatMinorAmount(line.unitPriceMinor, line.currency),
                    formatMinorAmount(line.lineTotalMinor, line.currency)
            ));
        }
        return result;
    }

    private static List<OrderTotalMailView> buildTotals(List<ShopOrder> orders) {
        Map<String, Integer> totalsByCurrency = new LinkedHashMap<>();
        for (ShopOrder order : orders) {
            String currencyCode = safe(order.getCurrency()).toUpperCase(Locale.ROOT);
            int amountMinor = Math.max(0, order.getGrossAmountMinor());
            totalsByCurrency.put(currencyCode, totalsByCurrency.getOrDefault(currencyCode, 0) + amountMinor);
        }

        List<OrderTotalMailView> totals = new ArrayList<>(totalsByCurrency.size());
        for (Map.Entry<String, Integer> entry : totalsByCurrency.entrySet()) {
            totals.add(new OrderTotalMailView(entry.getKey(), formatMinorAmount(entry.getValue(), entry.getKey())));
        }
        return totals;
    }

    private static String formatInstant(Instant value) {
        if (value == null) return "n/a";
        return DATE_TIME_FORMATTER.format(value);
    }

    private static String formatDurationDays(int days) {
        int safeDays = Math.max(0, days);
        if (safeDays == 1) return "1 day";
        return safeDays + " days";
    }

    private static String displayNameFor(AppUser user) {
        String displayName = safe(user.getDisplayName()).trim();
        if (!displayName.isEmpty()) return displayName;
        String email = safe(user.getEmail());
        int at = email.indexOf('@');
        if (at > 0) return email.substring(0, at);
        return "Player";
    }

    private static String normalizeBaseUrl(String rawBaseUrl) {
        String value = safe(rawBaseUrl).trim();
        if (value.isEmpty()) return "http://localhost:4200";
        if (value.contains("://")) return value;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("localhost") || lower.startsWith("127.0.0.1")) {
            return "http://" + value;
        }
        return "https://" + value;
    }

    private static String trimTrailingSlash(String value) {
        String normalized = safe(value).trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? "http://localhost:4200" : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isCoinsCurrency(String currency) {
        return "COINS".equalsIgnoreCase(safe(currency));
    }

    public record OrderLineMailView(
            String productName,
            String planName,
            int quantity,
            String unitPriceLabel,
            String lineTotalLabel
    ) {}

    public record OrderTotalMailView(
            String currency,
            String amountLabel
    ) {}

    private static final class AggregatedOrderLine {
        private final String productName;
        private final String planName;
        private final String currency;
        private final int unitPriceMinor;
        private int quantity;
        private int lineTotalMinor;

        private AggregatedOrderLine(String productName, String planName, String currency, int unitPriceMinor) {
            this.productName = productName;
            this.planName = planName;
            this.currency = currency;
            this.unitPriceMinor = Math.max(0, unitPriceMinor);
        }

        private void addQuantity(int value) {
            this.quantity += Math.max(0, value);
        }

        private void addLineTotal(int value) {
            this.lineTotalMinor += Math.max(0, value);
        }
    }
}
