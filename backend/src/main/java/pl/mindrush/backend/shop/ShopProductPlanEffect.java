package pl.mindrush.backend.shop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "shop_product_plan_effects",
        indexes = {
                @Index(name = "idx_shop_plan_effects_plan_sort", columnList = "plan_id, sort_order")
        }
)
public class ShopProductPlanEffect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private ShopProductPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect_type", nullable = false, length = 32)
    private ShopProductPlanEffectType effectType;

    @Column(name = "effect_code", length = 64)
    private String effectCode;

    @Column(name = "effect_value", nullable = false)
    private int effectValue;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getId() {
        return id;
    }

    public ShopProductPlan getPlan() {
        return plan;
    }

    public void setPlan(ShopProductPlan plan) {
        this.plan = plan;
    }

    public ShopProductPlanEffectType getEffectType() {
        return effectType;
    }

    public void setEffectType(ShopProductPlanEffectType effectType) {
        this.effectType = effectType;
    }

    public String getEffectCode() {
        return effectCode;
    }

    public void setEffectCode(String effectCode) {
        this.effectCode = effectCode;
    }

    public int getEffectValue() {
        return effectValue;
    }

    public void setEffectValue(int effectValue) {
        this.effectValue = effectValue;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
