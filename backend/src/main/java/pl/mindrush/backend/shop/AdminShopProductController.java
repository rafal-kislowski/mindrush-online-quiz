package pl.mindrush.backend.shop;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/admin/shop/products")
public class AdminShopProductController {

    private final AdminShopProductService service;
    private final ShopPricingConfigService pricingConfigService;
    private final ShopEffectCatalogService effectCatalogService;

    public AdminShopProductController(
            AdminShopProductService service,
            ShopPricingConfigService pricingConfigService,
            ShopEffectCatalogService effectCatalogService
    ) {
        this.service = service;
        this.pricingConfigService = pricingConfigService;
        this.effectCatalogService = effectCatalogService;
    }

    @GetMapping
    public ResponseEntity<List<AdminShopProductService.AdminProductListItem>> list() {
        return ResponseEntity.ok(service.listProducts());
    }

    @GetMapping("/config")
    public ResponseEntity<AdminShopConfigResponse> config() {
        return ResponseEntity.ok(new AdminShopConfigResponse(
                List.of(ShopProductPricingMode.SUBSCRIPTION, ShopProductPricingMode.ONE_TIME),
                pricingConfigService.pricingCurrencies().stream()
                        .map(item -> new PricingCurrencyResponse(
                                item.code(),
                                item.label(),
                                item.type(),
                                item.fractionDigits()
                        ))
                        .toList(),
                effectCatalogService.all().stream()
                        .map(item -> new PricingEffectResponse(
                                item.type(),
                                item.code(),
                                item.label(),
                                item.description()
                        ))
                        .toList()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminShopProductService.AdminProductDetail> get(@PathVariable("id") Long productId) {
        return ResponseEntity.ok(service.getProduct(productId));
    }

    @PostMapping
    public ResponseEntity<AdminShopProductService.AdminProductDetail> create(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(CREATED).body(service.createProduct(toInput(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AdminShopProductService.AdminProductDetail> update(
            @PathVariable("id") Long productId,
            @Valid @RequestBody ProductRequest request
    ) {
        return ResponseEntity.ok(service.updateProduct(productId, toInput(request)));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<AdminShopProductService.AdminProductDetail> setStatus(
            @PathVariable("id") Long productId,
            @RequestBody StatusRequest request
    ) {
        return ResponseEntity.ok(service.setStatus(productId, request == null ? null : request.status()));
    }

    private static AdminShopProductService.ProductInput toInput(ProductRequest request) {
        return new AdminShopProductService.ProductInput(
                request.code(),
                request.slug(),
                request.status(),
                request.pricingMode(),
                request.title(),
                request.subtitle(),
                request.description(),
                request.badgeLabel(),
                request.heroImageUrl(),
                request.checkoutEnabled(),
                request.sortOrder(),
                request.trustHighlights() == null ? List.of() : request.trustHighlights().stream()
                        .map(item -> new AdminShopProductService.TrustHighlightInput(
                                item.icon(),
                                item.title(),
                                item.detail()
                        ))
                        .toList(),
                request.advantages() == null ? List.of() : request.advantages().stream()
                        .map(item -> new AdminShopProductService.AdvantageInput(
                                item.icon(),
                                item.title(),
                                item.bullets()
                        ))
                        .toList(),
                request.pricingPlans() == null ? List.of() : request.pricingPlans().stream()
                        .map(item -> new AdminShopProductService.PlanInput(
                                item.code(),
                                item.label(),
                                item.durationDays(),
                                item.currency(),
                                item.grossAmountMinor(),
                                item.grantValue(),
                                item.grantUnit(),
                                item.teaser(),
                                item.effects() == null ? List.of() : item.effects().stream()
                                        .map(effect -> new AdminShopProductService.PlanEffectInput(
                                                effect.type(),
                                                effect.code(),
                                                effect.value()
                                        ))
                                        .toList()
                        ))
                        .toList()
        );
    }

    public record ProductRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_]+$") String code,
            @NotBlank @Size(max = 120) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
            ShopProductStatus status,
            ShopProductPricingMode pricingMode,
            @NotBlank @Size(max = 120) String title,
            @Size(max = 220) String subtitle,
            @Size(max = 2000) String description,
            @Size(max = 80) String badgeLabel,
            @Size(max = 500) String heroImageUrl,
            Boolean checkoutEnabled,
            Integer sortOrder,
            List<@Valid TrustHighlightRequest> trustHighlights,
            List<@Valid AdvantageRequest> advantages,
            List<@Valid PlanRequest> pricingPlans
    ) {}

    public record TrustHighlightRequest(
            @Size(max = 64) String icon,
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 220) String detail
    ) {}

    public record AdvantageRequest(
            @Size(max = 64) String icon,
            @NotBlank @Size(max = 120) String title,
            List<@Size(max = 240) String> bullets
    ) {}

    public record PlanRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9_]+$") String code,
            @NotBlank @Size(max = 120) String label,
            Integer durationDays,
            @NotBlank @Size(min = 3, max = 16) @Pattern(regexp = "^[A-Za-z0-9_]{3,16}$") String currency,
            Integer grossAmountMinor,
            Integer grantValue,
            @Size(max = 32) @Pattern(regexp = "^$|^[A-Za-z0-9_]{2,32}$") String grantUnit,
            @Size(max = 220) String teaser,
            List<@Valid PlanEffectRequest> effects
    ) {}

    public record PlanEffectRequest(
            ShopProductPlanEffectType type,
            @Size(max = 64) @Pattern(regexp = "^$|^[A-Za-z0-9_]{2,64}$") String code,
            Integer value
    ) {}

    public record StatusRequest(ShopProductStatus status) {}

    public record PricingCurrencyResponse(
            String code,
            String label,
            String type,
            int fractionDigits
    ) {}

    public record PricingEffectResponse(
            ShopProductPlanEffectType type,
            String code,
            String label,
            String description
    ) {}

    public record AdminShopConfigResponse(
            List<ShopProductPricingMode> pricingModes,
            List<PricingCurrencyResponse> pricingCurrencies,
            List<PricingEffectResponse> pricingEffects
    ) {}
}
