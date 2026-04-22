package pl.mindrush.backend.shop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "shop_product_plans",
        indexes = {
                @Index(name = "idx_shop_product_plans_product_sort", columnList = "product_id, sort_order")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_shop_product_plans_product_code", columnNames = {"product_id", "code"})
        }
)
public class ShopProductPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ShopProduct product;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "gross_amount_minor", nullable = false)
    private int grossAmountMinor;

    @Column(name = "grant_value")
    private Integer grantValue;

    @Column(name = "grant_unit", length = 32)
    private String grantUnit;

    @Column(name = "teaser", length = 220)
    private String teaser;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @OneToMany(mappedBy = "plan", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ShopProductPlanEffect> effects = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public ShopProduct getProduct() {
        return product;
    }

    public void setProduct(ShopProduct product) {
        this.product = product;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(int durationDays) {
        this.durationDays = durationDays;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public int getGrossAmountMinor() {
        return grossAmountMinor;
    }

    public void setGrossAmountMinor(int grossAmountMinor) {
        this.grossAmountMinor = grossAmountMinor;
    }

    public Integer getGrantValue() {
        return grantValue;
    }

    public void setGrantValue(Integer grantValue) {
        this.grantValue = grantValue;
    }

    public String getGrantUnit() {
        return grantUnit;
    }

    public void setGrantUnit(String grantUnit) {
        this.grantUnit = grantUnit;
    }

    public String getTeaser() {
        return teaser;
    }

    public void setTeaser(String teaser) {
        this.teaser = teaser;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public List<ShopProductPlanEffect> getEffects() {
        return effects;
    }

    public void setEffects(List<ShopProductPlanEffect> effects) {
        this.effects = effects == null ? new ArrayList<>() : effects;
    }
}
