package pl.mindrush.backend.shop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class AdminShopProductService {

    private static final Pattern PRODUCT_CODE_PATTERN = Pattern.compile("^[A-Z0-9_]+$");
    private static final Pattern PRODUCT_SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z0-9_]{3,16}$");
    private static final Pattern ICON_PATTERN = Pattern.compile("^[a-z0-9\\- ]+$", Pattern.CASE_INSENSITIVE);

    private final Clock clock;
    private final ShopProductRepository productRepository;
    private final ShopPricingConfigService pricingConfigService;
    private final ShopEffectCatalogService effectCatalogService;

    public AdminShopProductService(
            Clock clock,
            ShopProductRepository productRepository,
            ShopPricingConfigService pricingConfigService,
            ShopEffectCatalogService effectCatalogService
    ) {
        this.clock = clock;
        this.productRepository = productRepository;
        this.pricingConfigService = pricingConfigService;
        this.effectCatalogService = effectCatalogService;
    }

    @Transactional(readOnly = true)
    public List<AdminProductListItem> listProducts() {
        return productRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(product -> new AdminProductListItem(
                        product.getId(),
                        product.getCode(),
                        product.getSlug(),
                        product.getStatus(),
                        product.getTitle(),
                        product.getBadgeLabel(),
                        product.getHeroImageUrl(),
                        product.isCheckoutEnabled(),
                        product.getPlans().size(),
                        product.getAdvantages().size(),
                        lowestPriceMinor(product),
                        lowestPriceCurrency(product),
                        product.getUpdatedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminProductDetail getProduct(Long productId) {
        return toDetail(requireProduct(productId));
    }

    public AdminProductDetail createProduct(ProductInput input) {
        ShopProduct product = new ShopProduct();
        applyInput(product, input, true);
        return toDetail(productRepository.save(product));
    }

    public AdminProductDetail updateProduct(Long productId, ProductInput input) {
        ShopProduct product = requireProduct(productId);
        applyInput(product, input, false);
        return toDetail(productRepository.save(product));
    }

    public AdminProductDetail setStatus(Long productId, ShopProductStatus status) {
        if (status == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Status is required");
        }
        ShopProduct product = requireProduct(productId);
        product.setStatus(status);
        validatePublicationState(product);
        product.setUpdatedAt(clock.instant());
        return toDetail(productRepository.save(product));
    }

    private void applyInput(ShopProduct product, ProductInput input, boolean create) {
        if (input == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Product payload is required");
        }

        String code = normalizeProductCode(input.code());
        String slug = normalizeProductSlug(input.slug());
        String title = trimToNull(input.title());
        if (title == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Title is required");
        }

        if (create) {
            if (productRepository.existsByCode(code)) {
                throw new ResponseStatusException(CONFLICT, "Product code already exists");
            }
            if (productRepository.existsBySlug(slug)) {
                throw new ResponseStatusException(CONFLICT, "Product slug already exists");
            }
        } else {
            if (productRepository.existsByCodeAndIdNot(code, product.getId())) {
                throw new ResponseStatusException(CONFLICT, "Product code already exists");
            }
            if (productRepository.existsBySlugAndIdNot(slug, product.getId())) {
                throw new ResponseStatusException(CONFLICT, "Product slug already exists");
            }
        }

        ShopProductStatus status = input.status() == null ? ShopProductStatus.DRAFT : input.status();
        ShopProductPricingMode pricingMode = input.pricingMode() == null ? ShopProductPricingMode.SUBSCRIPTION : input.pricingMode();

        product.setCode(code);
        product.setSlug(slug);
        product.setStatus(status);
        product.setPricingMode(pricingMode);
        product.setTitle(title);
        product.setSubtitle(trimToNull(input.subtitle()));
        product.setDescription(trimToNull(input.description()));
        product.setBadgeLabel(trimToNull(input.badgeLabel()));
        product.setHeroImageUrl(trimToNull(input.heroImageUrl()));
        product.setCheckoutEnabled(Boolean.TRUE.equals(input.checkoutEnabled()));
        product.setSortOrder(input.sortOrder() == null ? 0 : input.sortOrder());

        Instant now = clock.instant();
        if (product.getCreatedAt() == null) {
            product.setCreatedAt(now);
        }
        product.setUpdatedAt(now);

        syncPlans(product, input.pricingPlans(), pricingMode);
        syncTrustHighlights(product, input.trustHighlights());
        syncAdvantages(product, input.advantages());
        validatePublicationState(product);
    }

    private void syncPlans(ShopProduct product, List<PlanInput> inputs, ShopProductPricingMode pricingMode) {
        List<ShopProductPlan> existingPlans = product.getPlans();
        var existingByCode = existingPlans.stream()
                .collect(java.util.stream.Collectors.toMap(ShopProductPlan::getCode, plan -> plan, (left, right) -> left));
        Set<String> codes = new HashSet<>();
        List<PlanInput> safeInputs = inputs == null ? List.of() : inputs;
        List<String> incomingCodes = new ArrayList<>(safeInputs.size());
        for (int i = 0; i < safeInputs.size(); i++) {
            PlanInput input = safeInputs.get(i);
            if (input == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Pricing plan payload is invalid");
            }

            String code = normalizePlanCode(input.code());
            if (!codes.add(code)) {
                throw new ResponseStatusException(BAD_REQUEST, "Plan codes must be unique within one product");
            }
            incomingCodes.add(code);

            String label = trimToNull(input.label());
            if (label == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Plan label is required");
            }

            Integer grossAmountMinor = input.grossAmountMinor();
            if (grossAmountMinor == null || grossAmountMinor < 0) {
                throw new ResponseStatusException(BAD_REQUEST, "Plan price must be 0 or greater");
            }

            String currency = normalizeCurrency(input.currency());
            List<PlanEffectInput> effects = normalizePlanEffects(input, pricingMode);
            int durationDays = deriveDurationDays(effects);
            EffectGrantSnapshot grantSnapshot = deriveGrantSnapshot(effects);

            ShopProductPlan plan = existingByCode.get(code);
            if (plan == null) {
                plan = new ShopProductPlan();
                plan.setProduct(product);
                existingPlans.add(plan);
            }
            plan.setCode(code);
            plan.setLabel(label);
            plan.setDurationDays(durationDays);
            plan.setCurrency(currency);
            plan.setGrossAmountMinor(grossAmountMinor);
            plan.setGrantValue(grantSnapshot.value());
            plan.setGrantUnit(grantSnapshot.unit());
            plan.setTeaser(trimToNull(input.teaser()));
            plan.setSortOrder(i);
            syncPlanEffects(plan, effects);
        }
        existingPlans.removeIf(plan -> !incomingCodes.contains(plan.getCode()));
    }

    private void syncTrustHighlights(ShopProduct product, List<TrustHighlightInput> inputs) {
        List<ShopProductTrustHighlight> highlights = new ArrayList<>();
        List<TrustHighlightInput> safeInputs = inputs == null ? List.of() : inputs;
        for (int i = 0; i < safeInputs.size(); i++) {
            TrustHighlightInput input = safeInputs.get(i);
            if (input == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Trust highlight payload is invalid");
            }

            String title = trimToNull(input.title());
            String detail = trimToNull(input.detail());
            if (title == null || detail == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Trust highlight title and detail are required");
            }

            ShopProductTrustHighlight highlight = new ShopProductTrustHighlight();
            highlight.setProduct(product);
            highlight.setIcon(normalizeIcon(input.icon()));
            highlight.setTitle(title);
            highlight.setDetail(detail);
            highlight.setSortOrder(i);
            highlights.add(highlight);
        }
        product.getTrustHighlights().clear();
        product.getTrustHighlights().addAll(highlights);
    }

    private void syncAdvantages(ShopProduct product, List<AdvantageInput> inputs) {
        List<ShopProductAdvantage> advantages = new ArrayList<>();
        List<AdvantageInput> safeInputs = inputs == null ? List.of() : inputs;
        for (int i = 0; i < safeInputs.size(); i++) {
            AdvantageInput input = safeInputs.get(i);
            if (input == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Advantage payload is invalid");
            }

            String title = trimToNull(input.title());
            if (title == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Advantage title is required");
            }

            ShopProductAdvantage advantage = new ShopProductAdvantage();
            advantage.setProduct(product);
            advantage.setIcon(normalizeIcon(input.icon()));
            advantage.setTitle(title);
            advantage.setSortOrder(i);

            List<ShopProductAdvantageBullet> bullets = new ArrayList<>();
            List<String> safeBullets = input.bullets() == null ? List.of() : input.bullets();
            for (int bulletIndex = 0; bulletIndex < safeBullets.size(); bulletIndex++) {
                String value = trimToNull(safeBullets.get(bulletIndex));
                if (value == null) {
                    throw new ResponseStatusException(BAD_REQUEST, "Advantage bullet cannot be empty");
                }
                ShopProductAdvantageBullet bullet = new ShopProductAdvantageBullet();
                bullet.setAdvantage(advantage);
                bullet.setValue(value);
                bullet.setSortOrder(bulletIndex);
                bullets.add(bullet);
            }
            advantage.getBullets().clear();
            advantage.getBullets().addAll(bullets);
            advantages.add(advantage);
        }
        product.getAdvantages().clear();
        product.getAdvantages().addAll(advantages);
    }

    private void validatePublicationState(ShopProduct product) {
        if (product == null) return;
        if (product.getStatus() != ShopProductStatus.ACTIVE) return;
        if (product.isCheckoutEnabled() && product.getPlans().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Active checkout-enabled product must have at least one pricing plan");
        }
    }

    private AdminProductDetail toDetail(ShopProduct product) {
        return new AdminProductDetail(
                product.getId(),
                product.getCode(),
                product.getSlug(),
                product.getStatus(),
                product.getPricingMode(),
                product.getTitle(),
                product.getSubtitle(),
                product.getDescription(),
                product.getBadgeLabel(),
                product.getHeroImageUrl(),
                product.isCheckoutEnabled(),
                product.getSortOrder(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                product.getTrustHighlights().stream()
                        .map(item -> new TrustHighlightDetail(
                                item.getId(),
                                item.getIcon(),
                                item.getTitle(),
                                item.getDetail(),
                                item.getSortOrder()
                        ))
                        .toList(),
                product.getAdvantages().stream()
                        .map(item -> new AdvantageDetail(
                                item.getId(),
                                item.getIcon(),
                                item.getTitle(),
                                item.getBullets().stream()
                                        .map(bullet -> new AdvantageBulletDetail(
                                                bullet.getId(),
                                                bullet.getValue(),
                                                bullet.getSortOrder()
                                        ))
                                        .toList(),
                                item.getSortOrder()
                        ))
                        .toList(),
                product.getPlans().stream()
                        .map(plan -> new PlanDetail(
                                plan.getId(),
                                plan.getCode(),
                                plan.getLabel(),
                                plan.getDurationDays(),
                                plan.getCurrency(),
                                plan.getGrossAmountMinor(),
                                plan.getGrantValue(),
                                plan.getGrantUnit(),
                                plan.getTeaser(),
                                plan.getSortOrder(),
                                plan.getEffects().stream()
                                        .map(effect -> new PlanEffectDetail(
                                                effect.getId(),
                                                effect.getEffectType(),
                                                effect.getEffectCode(),
                                                effect.getEffectValue(),
                                                effect.getSortOrder()
                                        ))
                                        .toList()
                        ))
                        .toList()
        );
    }

    private ShopProduct requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Product not found"));
    }

    private long lowestPriceMinor(ShopProduct product) {
        return product.getPlans().stream()
                .mapToLong(ShopProductPlan::getGrossAmountMinor)
                .min()
                .orElse(-1L);
    }

    private String lowestPriceCurrency(ShopProduct product) {
        return product.getPlans().stream()
                .findFirst()
                .map(ShopProductPlan::getCurrency)
                .orElse(null);
    }

    private static String normalizeProductCode(String raw) {
        String normalized = ShopCatalogService.normalizeCode(raw);
        if (normalized.isBlank() || !PRODUCT_CODE_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "Product code is invalid");
        }
        return normalized;
    }

    private static String normalizeProductSlug(String raw) {
        String normalized = ShopCatalogService.normalizeSlug(raw);
        if (normalized.isBlank() || !PRODUCT_SLUG_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "Product slug is invalid");
        }
        return normalized;
    }

    private static String normalizePlanCode(String raw) {
        String normalized = ShopCatalogService.normalizeCode(raw);
        if (normalized.isBlank() || !PRODUCT_CODE_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "Plan code is invalid");
        }
        return normalized;
    }

    private String normalizeCurrency(String raw) {
        String normalized = String.valueOf(raw == null ? "" : raw).trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "Plan currency is invalid");
        }
        if (!pricingConfigService.isAllowedCurrency(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Plan currency is not configured");
        }
        return normalized;
    }

    private List<PlanEffectInput> normalizePlanEffects(PlanInput planInput, ShopProductPricingMode pricingMode) {
        List<PlanEffectInput> effects = new ArrayList<>();
        List<PlanEffectInput> inputEffects = planInput.effects() == null ? List.of() : planInput.effects();
        for (PlanEffectInput inputEffect : inputEffects) {
            if (inputEffect == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Plan effect payload is invalid");
            }
            PlanEffectInput normalized = normalizePlanEffect(inputEffect.type(), inputEffect.code(), inputEffect.value());
            effects.add(normalized);
        }

        if (effects.isEmpty()) {
            Integer legacyDurationDays = planInput.durationDays();
            Integer legacyGrantValue = planInput.grantValue();
            String legacyGrantUnit = planInput.grantUnit();

            if (legacyDurationDays != null && legacyDurationDays > 0) {
                String durationCode = effectCatalogService.normalizeAndValidateCode(ShopProductPlanEffectType.DURATION_DAYS, null, false);
                effects.add(new PlanEffectInput(ShopProductPlanEffectType.DURATION_DAYS, durationCode, legacyDurationDays));
            }
            if (legacyGrantValue != null && legacyGrantValue > 0) {
                String unit = effectCatalogService.normalizeAndValidateCode(ShopProductPlanEffectType.RESOURCE_GRANT, legacyGrantUnit, true);
                effects.add(new PlanEffectInput(ShopProductPlanEffectType.RESOURCE_GRANT, unit, legacyGrantValue));
            }
        }

        if (pricingMode == ShopProductPricingMode.SUBSCRIPTION) {
            boolean hasDurationEffect = effects.stream()
                    .anyMatch(effect -> effect.type() == ShopProductPlanEffectType.DURATION_DAYS && effect.value() != null && effect.value() > 0);
            if (!hasDurationEffect) {
                throw new ResponseStatusException(BAD_REQUEST, "Subscription option requires a DURATION_DAYS effect");
            }
        } else {
            if (effects.isEmpty()) {
                throw new ResponseStatusException(BAD_REQUEST, "One-time option must define at least one effect");
            }
        }

        return effects;
    }

    private PlanEffectInput normalizePlanEffect(
            ShopProductPlanEffectType rawType,
            String rawCode,
            Integer rawValue
    ) {
        ShopProductPlanEffectType type = rawType == null ? null : rawType;
        if (type == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Plan effect type is required");
        }
        int value = rawValue == null ? 0 : rawValue;
        if (value <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Plan effect value must be greater than 0");
        }

        if (type == ShopProductPlanEffectType.DURATION_DAYS) {
            String code = effectCatalogService.normalizeAndValidateCode(type, rawCode, false);
            return new PlanEffectInput(type, code, value);
        }

        String code = effectCatalogService.normalizeAndValidateCode(type, rawCode, true);
        return new PlanEffectInput(type, code, value);
    }

    private static int deriveDurationDays(List<PlanEffectInput> effects) {
        return effects.stream()
                .filter(effect -> effect.type() == ShopProductPlanEffectType.DURATION_DAYS)
                .mapToInt(effect -> effect.value() == null ? 0 : effect.value())
                .sum();
    }

    private static EffectGrantSnapshot deriveGrantSnapshot(List<PlanEffectInput> effects) {
        for (PlanEffectInput effect : effects) {
            if (effect.type() == ShopProductPlanEffectType.RESOURCE_GRANT) {
                return new EffectGrantSnapshot(effect.value(), effect.code());
            }
        }
        return new EffectGrantSnapshot(null, null);
    }

    private static void syncPlanEffects(ShopProductPlan plan, List<PlanEffectInput> effects) {
        List<ShopProductPlanEffect> current = plan.getEffects();
        current.clear();
        for (int i = 0; i < effects.size(); i++) {
            PlanEffectInput input = effects.get(i);
            ShopProductPlanEffect effect = new ShopProductPlanEffect();
            effect.setPlan(plan);
            effect.setEffectType(input.type());
            effect.setEffectCode(input.code());
            effect.setEffectValue(input.value() == null ? 0 : input.value());
            effect.setSortOrder(i);
            current.add(effect);
        }
    }

    private static String normalizeIcon(String raw) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return null;
        }
        if (!ICON_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "Icon value is invalid");
        }
        return normalized;
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim();
        return normalized.isBlank() ? null : normalized;
    }

    public record ProductInput(
            String code,
            String slug,
            ShopProductStatus status,
            ShopProductPricingMode pricingMode,
            String title,
            String subtitle,
            String description,
            String badgeLabel,
            String heroImageUrl,
            Boolean checkoutEnabled,
            Integer sortOrder,
            List<TrustHighlightInput> trustHighlights,
            List<AdvantageInput> advantages,
            List<PlanInput> pricingPlans
    ) {}

    public record TrustHighlightInput(
            String icon,
            String title,
            String detail
    ) {}

    public record AdvantageInput(
            String icon,
            String title,
            List<String> bullets
    ) {}

    public record PlanInput(
            String code,
            String label,
            Integer durationDays,
            String currency,
            Integer grossAmountMinor,
            Integer grantValue,
            String grantUnit,
            String teaser,
            List<PlanEffectInput> effects
    ) {}

    public record PlanEffectInput(
            ShopProductPlanEffectType type,
            String code,
            Integer value
    ) {}

    public record AdminProductListItem(
            Long id,
            String code,
            String slug,
            ShopProductStatus status,
            String title,
            String badgeLabel,
            String heroImageUrl,
            boolean checkoutEnabled,
            int planCount,
            int advantageCount,
            long lowestPriceMinor,
            String lowestPriceCurrency,
            Instant updatedAt
    ) {}

    public record AdminProductDetail(
            Long id,
            String code,
            String slug,
            ShopProductStatus status,
            ShopProductPricingMode pricingMode,
            String title,
            String subtitle,
            String description,
            String badgeLabel,
            String heroImageUrl,
            boolean checkoutEnabled,
            int sortOrder,
            Instant createdAt,
            Instant updatedAt,
            List<TrustHighlightDetail> trustHighlights,
            List<AdvantageDetail> advantages,
            List<PlanDetail> pricingPlans
    ) {}

    public record TrustHighlightDetail(
            Long id,
            String icon,
            String title,
            String detail,
            int sortOrder
    ) {}

    public record AdvantageDetail(
            Long id,
            String icon,
            String title,
            List<AdvantageBulletDetail> bullets,
            int sortOrder
    ) {}

    public record AdvantageBulletDetail(
            Long id,
            String value,
            int sortOrder
    ) {}

    public record PlanDetail(
            Long id,
            String code,
            String label,
            int durationDays,
            String currency,
            int grossAmountMinor,
            Integer grantValue,
            String grantUnit,
            String teaser,
            int sortOrder,
            List<PlanEffectDetail> effects
    ) {}

    public record PlanEffectDetail(
            Long id,
            ShopProductPlanEffectType type,
            String code,
            int value,
            int sortOrder
    ) {}

    private record EffectGrantSnapshot(
            Integer value,
            String unit
    ) {}
}
