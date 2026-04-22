package pl.mindrush.backend.shop;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class ShopCatalogSeeder implements CommandLineRunner {

    private final ShopProductRepository productRepository;
    private final AdminShopProductService adminShopProductService;

    public ShopCatalogSeeder(
            ShopProductRepository productRepository,
            AdminShopProductService adminShopProductService
    ) {
        this.productRepository = productRepository;
        this.adminShopProductService = adminShopProductService;
    }

    @Override
    public void run(String... args) {
        List<AdminShopProductService.ProductInput> templates = List.of(
                premiumTemplate(),
                buyCoinsTemplate(),
                xpBoosterTemplate(),
                rpBoosterTemplate(),
                coinsBoosterTemplate(),
                tripleBoosterTemplate()
        );

        for (AdminShopProductService.ProductInput template : templates) {
            String code = ShopCatalogService.normalizeCode(template.code());
            ShopProduct existing = productRepository.findByCode(code).orElse(null);
            if (existing == null) {
                adminShopProductService.createProduct(template);
                continue;
            }
            adminShopProductService.updateProduct(existing.getId(), template);
        }
    }

    private static AdminShopProductService.ProductInput premiumTemplate() {
        return new AdminShopProductService.ProductInput(
                "PREMIUM",
                "premium",
                ShopProductStatus.ACTIVE,
                ShopProductPricingMode.SUBSCRIPTION,
                "MindRush Premium",
                "Premium account access with stackable time.",
                "Unlock premium account perks with flexible time plans. Every successful purchase extends active premium expiry.",
                "Premium access",
                "/shop/shop_premium_account.png",
                true,
                10,
                List.of(
                        trust("fa-shield-halved", "Secure checkout", "Server-side validation and state transitions"),
                        trust("fa-bolt", "Instant activation", "Premium activates right after successful payment"),
                        trust("fa-hourglass-half", "Stackable time", "Each next purchase extends premium time"),
                        trust("fa-database", "No data loss", "Existing content remains after expiry")
                ),
                List.of(
                        advantage("fa-layer-group", "Creator limits", List.of("Higher quiz and submission limits", "Expanded premium workflow")),
                        advantage("fa-list-check", "Bigger quizzes", List.of("More questions and media per quiz", "Better long-form quiz support")),
                        advantage("fa-stopwatch", "Longer sessions", List.of("Higher gameplay timer caps", "More questions per match")),
                        advantage("fa-certificate", "Premium identity", List.of("Premium profile status", "Premium account visibility in app"))
                ),
                List.of(
                        plan("DAY_1", "1 day", "PLN", 499, "Quick premium access", List.of(duration("PREMIUM_ACCESS", 1))),
                        plan("DAY_3", "3 days", "PLN", 1199, "Weekend premium", List.of(duration("PREMIUM_ACCESS", 3))),
                        plan("DAY_7", "7 days", "PLN", 1999, "Weekly premium", List.of(duration("PREMIUM_ACCESS", 7))),
                        plan("MONTH_1", "1 month", "PLN", 4999, "Most popular plan", List.of(duration("PREMIUM_ACCESS", 30))),
                        plan("MONTH_3", "3 months", "PLN", 12999, "Long-term creator boost", List.of(duration("PREMIUM_ACCESS", 90))),
                        plan("YEAR_1", "1 year", "PLN", 34999, "Best yearly value", List.of(duration("PREMIUM_ACCESS", 365)))
                )
        );
    }

    private static AdminShopProductService.ProductInput buyCoinsTemplate() {
        return new AdminShopProductService.ProductInput(
                "BUY_COINS",
                "buy-coins",
                ShopProductStatus.ACTIVE,
                ShopProductPricingMode.ONE_TIME,
                "Buy Extra Coins",
                "Top up your in-game currency instantly.",
                "Purchase coin packs with real money and use them for boosters and coin-priced offers.",
                "Coins top-up",
                "/shop/shop_coins_booster.png",
                true,
                20,
                List.of(
                        trust("fa-coins", "Instant grant", "Coins are granted right after payment"),
                        trust("fa-receipt", "Order tracking", "Each purchase has complete order history"),
                        trust("fa-lock", "Safe flow", "Checkout and fulfillment validated on backend"),
                        trust("fa-wallet", "Ready to use", "Balance updates immediately after completion")
                ),
                List.of(
                        advantage("fa-sack-dollar", "Flexible packs", List.of("Starter to high-volume options", "Clear pricing by tier")),
                        advantage("fa-store", "Shop ready", List.of("Coins can be used instantly", "No extra conversion step")),
                        advantage("fa-bolt", "Fast processing", List.of("Immediate balance update", "Consistent order lifecycle")),
                        advantage("fa-scale-balanced", "Transparent value", List.of("Visible pack sizes", "Comparable cost per bundle"))
                ),
                List.of(
                        plan("PACK_50K", "50K Coins", "PLN", 1099, "Starter top-up", List.of(grant("COINS", 50_000))),
                        plan("PACK_100K", "100K Coins", "PLN", 1999, "Popular entry pack", List.of(grant("COINS", 100_000))),
                        plan("PACK_300K", "300K Coins", "PLN", 4999, "Balanced value", List.of(grant("COINS", 300_000))),
                        plan("PACK_500K", "500K Coins", "PLN", 7999, "Power user pack", List.of(grant("COINS", 500_000))),
                        plan("PACK_1M", "1M Coins", "PLN", 14999, "High-volume pack", List.of(grant("COINS", 1_000_000))),
                        plan("PACK_2M", "2M Coins", "PLN", 26999, "Maximum standard pack", List.of(grant("COINS", 2_000_000)))
                )
        );
    }

    private static AdminShopProductService.ProductInput xpBoosterTemplate() {
        return new AdminShopProductService.ProductInput(
                "XP_BOOSTER",
                "xp-booster",
                ShopProductStatus.ACTIVE,
                ShopProductPricingMode.ONE_TIME,
                "XP Booster",
                "Earn more XP for a selected duration.",
                "Spend game coins to activate XP boost. Active time stacks when buying again.",
                "Coins only",
                "/shop/shop_exp_booster.png",
                true,
                30,
                List.of(
                        trust("fa-bolt", "Stacking duration", "Active time extends on repeated purchases"),
                        trust("fa-chart-line", "Faster progression", "XP rewards are boosted while active"),
                        trust("fa-shield-halved", "Server-side secure", "Boost state validated on backend"),
                        trust("fa-clock", "Precise expiry", "Exact bonus end-time is tracked")
                ),
                List.of(
                        advantage("fa-star", "Level up faster", List.of("Higher XP gains", "Useful for progression sessions")),
                        advantage("fa-hourglass-half", "Flexible timing", List.of("Short and long duration options", "Stackable active window")),
                        advantage("fa-ranking-star", "Better momentum", List.of("Maintain faster account growth", "Ideal for event sessions")),
                        advantage("fa-list-check", "Predictable effect", List.of("Consistent multiplier behavior", "Clear expiration visibility"))
                ),
                List.of(
                        plan("DAY_1", "1 day", "COINS", 75_000, "Quick XP boost", List.of(duration("XP_BOOST", 1))),
                        plan("DAY_2", "2 days", "COINS", 140_000, "Short XP grind", List.of(duration("XP_BOOST", 2))),
                        plan("DAY_3", "3 days", "COINS", 199_000, "Weekend XP boost", List.of(duration("XP_BOOST", 3))),
                        plan("DAY_7", "7 days", "COINS", 399_000, "Weekly XP boost", List.of(duration("XP_BOOST", 7))),
                        plan("DAY_14", "14 days", "COINS", 749_000, "Long XP streak", List.of(duration("XP_BOOST", 14))),
                        plan("DAY_30", "30 days", "COINS", 1_499_000, "Monthly XP boost", List.of(duration("XP_BOOST", 30)))
                )
        );
    }

    private static AdminShopProductService.ProductInput rpBoosterTemplate() {
        return new AdminShopProductService.ProductInput(
                "RP_BOOSTER",
                "rp-booster",
                ShopProductStatus.ACTIVE,
                ShopProductPricingMode.ONE_TIME,
                "Rank Points Booster",
                "Increase rank points gain for a selected duration.",
                "Spend game coins to activate rank points boost. Active time stacks when buying again.",
                "Coins only",
                "/shop/shop_rp_booster.png",
                true,
                40,
                List.of(
                        trust("fa-bolt", "Stacking duration", "Active time extends on repeated purchases"),
                        trust("fa-trophy", "Rank acceleration", "Rank points rewards are boosted while active"),
                        trust("fa-shield-halved", "Server-side secure", "Boost state validated on backend"),
                        trust("fa-clock", "Precise expiry", "Exact bonus end-time is tracked")
                ),
                List.of(
                        advantage("fa-ranking-star", "Climb rankings faster", List.of("Higher RP gains", "Faster ladder progression")),
                        advantage("fa-hourglass-half", "Flexible timing", List.of("Short and long duration options", "Stackable active window")),
                        advantage("fa-bolt", "Session efficiency", List.of("Useful in ranked streaks", "Improves reward tempo")),
                        advantage("fa-list-check", "Predictable effect", List.of("Consistent multiplier behavior", "Clear expiration visibility"))
                ),
                List.of(
                        plan("DAY_1", "1 day", "COINS", 75_000, "Quick RP boost", List.of(duration("RP_BOOST", 1))),
                        plan("DAY_2", "2 days", "COINS", 140_000, "Short rank push", List.of(duration("RP_BOOST", 2))),
                        plan("DAY_3", "3 days", "COINS", 199_000, "Weekend rank push", List.of(duration("RP_BOOST", 3))),
                        plan("DAY_7", "7 days", "COINS", 399_000, "Weekly RP boost", List.of(duration("RP_BOOST", 7))),
                        plan("DAY_14", "14 days", "COINS", 749_000, "Long RP streak", List.of(duration("RP_BOOST", 14))),
                        plan("DAY_30", "30 days", "COINS", 1_499_000, "Monthly RP boost", List.of(duration("RP_BOOST", 30)))
                )
        );
    }

    private static AdminShopProductService.ProductInput coinsBoosterTemplate() {
        return new AdminShopProductService.ProductInput(
                "COINS_BOOSTER",
                "coins-booster",
                ShopProductStatus.ACTIVE,
                ShopProductPricingMode.ONE_TIME,
                "Coins Booster",
                "Earn extra coins from gameplay rewards.",
                "Spend game coins to activate coins reward boost. Active time stacks when buying again.",
                "Coins only",
                "/shop/shop_coins_booster.png",
                true,
                50,
                List.of(
                        trust("fa-bolt", "Stacking duration", "Active time extends on repeated purchases"),
                        trust("fa-coins", "Higher rewards", "Coins rewards are boosted while active"),
                        trust("fa-shield-halved", "Server-side secure", "Boost state validated on backend"),
                        trust("fa-clock", "Precise expiry", "Exact bonus end-time is tracked")
                ),
                List.of(
                        advantage("fa-sack-dollar", "Farm coins faster", List.of("Higher coin gains", "Efficient currency growth")),
                        advantage("fa-hourglass-half", "Flexible timing", List.of("Short and long duration options", "Stackable active window")),
                        advantage("fa-bolt", "Session efficiency", List.of("Useful during long play sessions", "Improves reward tempo")),
                        advantage("fa-list-check", "Predictable effect", List.of("Consistent multiplier behavior", "Clear expiration visibility"))
                ),
                List.of(
                        plan("DAY_1", "1 day", "COINS", 75_000, "Quick coins boost", List.of(duration("COINS_BOOST", 1))),
                        plan("DAY_2", "2 days", "COINS", 140_000, "Short coins grind", List.of(duration("COINS_BOOST", 2))),
                        plan("DAY_3", "3 days", "COINS", 199_000, "Weekend coins boost", List.of(duration("COINS_BOOST", 3))),
                        plan("DAY_7", "7 days", "COINS", 399_000, "Weekly coins boost", List.of(duration("COINS_BOOST", 7))),
                        plan("DAY_14", "14 days", "COINS", 749_000, "Long coins streak", List.of(duration("COINS_BOOST", 14))),
                        plan("DAY_30", "30 days", "COINS", 1_499_000, "Monthly coins boost", List.of(duration("COINS_BOOST", 30)))
                )
        );
    }

    private static AdminShopProductService.ProductInput tripleBoosterTemplate() {
        return new AdminShopProductService.ProductInput(
                "TRIPLE_BOOSTER",
                "triple-booster",
                ShopProductStatus.ACTIVE,
                ShopProductPricingMode.ONE_TIME,
                "3-in-1 Booster",
                "XP + Rank Points + Coins in one package.",
                "Activate all three boosters together in a single purchase. Duration stacks when buying again.",
                "Coins only",
                "/shop/shop_exp_coins_rp_booster.png",
                true,
                60,
                List.of(
                        trust("fa-bolt", "Triple stacking", "XP, RP and Coins boosts stack together"),
                        trust("fa-layer-group", "One purchase", "Three active boosts applied at once"),
                        trust("fa-shield-halved", "Server-side secure", "All effects validated on backend"),
                        trust("fa-clock", "Precise expiry", "Exact bonus end-time is tracked")
                ),
                List.of(
                        advantage("fa-gauge-high", "Maximum progression", List.of("Faster account growth", "Higher overall reward output")),
                        advantage("fa-list-check", "Single action setup", List.of("One checkout for 3 effects", "Simple maintenance flow")),
                        advantage("fa-hourglass-half", "Flexible timing", List.of("Short and long duration options", "Stackable active window")),
                        advantage("fa-crown", "Premium boost profile", List.of("Great with premium account", "Strong value for heavy players"))
                ),
                List.of(
                        plan("DAY_1", "1 day", "COINS", 199_000, "Starter combo boost", tripleDurationEffects(1)),
                        plan("DAY_2", "2 days", "COINS", 369_000, "Short combo streak", tripleDurationEffects(2)),
                        plan("DAY_3", "3 days", "COINS", 529_000, "Weekend combo boost", tripleDurationEffects(3)),
                        plan("DAY_7", "7 days", "COINS", 999_000, "Weekly combo boost", tripleDurationEffects(7)),
                        plan("DAY_14", "14 days", "COINS", 1_899_000, "Long combo streak", tripleDurationEffects(14)),
                        plan("DAY_30", "30 days", "COINS", 3_699_000, "Monthly combo boost", tripleDurationEffects(30))
                )
        );
    }

    private static List<AdminShopProductService.PlanEffectInput> tripleDurationEffects(int days) {
        return List.of(
                duration("XP_BOOST", days),
                duration("RP_BOOST", days),
                duration("COINS_BOOST", days)
        );
    }

    private static AdminShopProductService.TrustHighlightInput trust(String icon, String title, String detail) {
        return new AdminShopProductService.TrustHighlightInput(icon, title, detail);
    }

    private static AdminShopProductService.AdvantageInput advantage(String icon, String title, List<String> bullets) {
        return new AdminShopProductService.AdvantageInput(icon, title, bullets);
    }

    private static AdminShopProductService.PlanInput plan(
            String code,
            String label,
            String currency,
            int grossAmountMinor,
            String teaser,
            List<AdminShopProductService.PlanEffectInput> effects
    ) {
        return new AdminShopProductService.PlanInput(
                code,
                label,
                null,
                currency,
                grossAmountMinor,
                null,
                null,
                teaser,
                effects
        );
    }

    private static AdminShopProductService.PlanEffectInput duration(String code, int days) {
        return new AdminShopProductService.PlanEffectInput(
                ShopProductPlanEffectType.DURATION_DAYS,
                code,
                days
        );
    }

    private static AdminShopProductService.PlanEffectInput grant(String code, int value) {
        return new AdminShopProductService.PlanEffectInput(
                ShopProductPlanEffectType.RESOURCE_GRANT,
                code,
                value
        );
    }
}
