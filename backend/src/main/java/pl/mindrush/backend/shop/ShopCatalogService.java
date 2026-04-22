package pl.mindrush.backend.shop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional(readOnly = true)
public class ShopCatalogService {

    private final ShopProductRepository productRepository;

    public ShopCatalogService(ShopProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public CatalogResponse getCatalog() {
        return new CatalogResponse(
                productRepository.findAllByStatusOrderBySortOrderAscUpdatedAtDesc(ShopProductStatus.ACTIVE).stream()
                        .map(this::toView)
                        .toList()
        );
    }

    public ProductView getProductBySlug(String slug) {
        return toView(requireActiveProduct(slug));
    }

    public ShopProduct requireActiveProduct(String slug) {
        String normalizedSlug = normalizeSlug(slug);
        return productRepository.findBySlugAndStatus(normalizedSlug, ShopProductStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Product not found"));
    }

    public ShopProduct requireCheckoutProduct(String slug) {
        ShopProduct product = requireActiveProduct(slug);
        if (!product.isCheckoutEnabled()) {
            throw new ResponseStatusException(BAD_REQUEST, "Checkout is disabled for this product");
        }
        return product;
    }

    public ShopProductPlan requirePlan(ShopProduct product, String planCode) {
        if (product == null) {
            throw new ResponseStatusException(NOT_FOUND, "Product not found");
        }
        String normalizedPlanCode = normalizeCode(planCode);
        return product.getPlans().stream()
                .filter(plan -> normalizedPlanCode.equals(plan.getCode()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Pricing plan not found"));
    }

    private ProductView toView(ShopProduct product) {
        return new ProductView(
                product.getCode(),
                product.getSlug(),
                product.getPricingMode(),
                product.getTitle(),
                product.getSubtitle(),
                product.getDescription(),
                product.getBadgeLabel(),
                product.getHeroImageUrl(),
                product.isCheckoutEnabled(),
                product.getTrustHighlights().stream()
                        .map(item -> new TrustHighlightView(
                                item.getIcon(),
                                item.getTitle(),
                                item.getDetail()
                        ))
                        .toList(),
                product.getAdvantages().stream()
                        .map(item -> new AdvantageView(
                                item.getIcon(),
                                item.getTitle(),
                                item.getBullets().stream()
                                        .map(ShopProductAdvantageBullet::getValue)
                                        .toList()
                        ))
                        .toList(),
                product.getPlans().stream()
                        .map(plan -> new PricingPlanView(
                                plan.getCode(),
                                plan.getLabel(),
                                plan.getDurationDays(),
                                plan.getCurrency(),
                                plan.getGrossAmountMinor(),
                                plan.getGrantValue(),
                                plan.getGrantUnit(),
                                plan.getTeaser(),
                                plan.getEffects().stream()
                                        .map(effect -> new PlanEffectView(
                                                effect.getEffectType(),
                                                effect.getEffectCode(),
                                                effect.getEffectValue()
                                        ))
                                        .toList()
                        ))
                        .toList()
        );
    }

    public static String normalizeCode(String raw) {
        return String.valueOf(raw == null ? "" : raw).trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeSlug(String raw) {
        return String.valueOf(raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
    }

    public record CatalogResponse(List<ProductView> items) {}

    public record ProductView(
            String code,
            String slug,
            ShopProductPricingMode pricingMode,
            String title,
            String subtitle,
            String description,
            String badgeLabel,
            String heroImageUrl,
            boolean checkoutEnabled,
            List<TrustHighlightView> trustHighlights,
            List<AdvantageView> advantages,
            List<PricingPlanView> pricingPlans
    ) {}

    public record TrustHighlightView(
            String icon,
            String title,
            String detail
    ) {}

    public record AdvantageView(
            String icon,
            String title,
            List<String> bullets
    ) {}

    public record PricingPlanView(
            String code,
            String label,
            int durationDays,
            String currency,
            int grossAmountMinor,
            Integer grantValue,
            String grantUnit,
            String teaser,
            List<PlanEffectView> effects
    ) {}

    public record PlanEffectView(
            ShopProductPlanEffectType type,
            String code,
            int value
    ) {}
}
