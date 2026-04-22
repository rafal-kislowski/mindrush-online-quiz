package pl.mindrush.backend.shop;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "shop_products",
        indexes = {
                @Index(name = "idx_shop_products_status_sort", columnList = "status, sort_order, updated_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_shop_products_code", columnNames = "code"),
                @UniqueConstraint(name = "uq_shop_products_slug", columnNames = "slug")
        }
)
public class ShopProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ShopProductStatus status = ShopProductStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_mode", nullable = false, length = 32)
    private ShopProductPricingMode pricingMode = ShopProductPricingMode.SUBSCRIPTION;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "subtitle", length = 220)
    private String subtitle;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "badge_label", length = 80)
    private String badgeLabel;

    @Column(name = "hero_image_url", length = 500)
    private String heroImageUrl;

    @Column(name = "checkout_enabled", nullable = false)
    private boolean checkoutEnabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ShopProductPlan> plans = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ShopProductTrustHighlight> trustHighlights = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ShopProductAdvantage> advantages = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public ShopProductStatus getStatus() {
        return status;
    }

    public void setStatus(ShopProductStatus status) {
        this.status = status == null ? ShopProductStatus.DRAFT : status;
    }

    public ShopProductPricingMode getPricingMode() {
        return pricingMode;
    }

    public void setPricingMode(ShopProductPricingMode pricingMode) {
        this.pricingMode = pricingMode == null ? ShopProductPricingMode.SUBSCRIPTION : pricingMode;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBadgeLabel() {
        return badgeLabel;
    }

    public void setBadgeLabel(String badgeLabel) {
        this.badgeLabel = badgeLabel;
    }

    public String getHeroImageUrl() {
        return heroImageUrl;
    }

    public void setHeroImageUrl(String heroImageUrl) {
        this.heroImageUrl = heroImageUrl;
    }

    public boolean isCheckoutEnabled() {
        return checkoutEnabled;
    }

    public void setCheckoutEnabled(boolean checkoutEnabled) {
        this.checkoutEnabled = checkoutEnabled;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<ShopProductPlan> getPlans() {
        return plans;
    }

    public void setPlans(List<ShopProductPlan> plans) {
        this.plans = plans == null ? new ArrayList<>() : plans;
    }

    public List<ShopProductTrustHighlight> getTrustHighlights() {
        return trustHighlights;
    }

    public void setTrustHighlights(List<ShopProductTrustHighlight> trustHighlights) {
        this.trustHighlights = trustHighlights == null ? new ArrayList<>() : trustHighlights;
    }

    public List<ShopProductAdvantage> getAdvantages() {
        return advantages;
    }

    public void setAdvantages(List<ShopProductAdvantage> advantages) {
        this.advantages = advantages == null ? new ArrayList<>() : advantages;
    }
}
