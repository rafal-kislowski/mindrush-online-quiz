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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "shop_order_effects",
        indexes = {
                @Index(name = "idx_shop_order_effects_order_sort", columnList = "order_id, sort_order")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_shop_order_effects_order_sort", columnNames = {"order_id", "sort_order"})
        }
)
public class ShopOrderEffect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private ShopOrder order;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect_type", nullable = false, length = 32)
    private ShopProductPlanEffectType effectType;

    @Column(name = "effect_code", length = 64)
    private String effectCode;

    @Column(name = "effect_value", nullable = false)
    private int effectValue;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "applied_at")
    private Instant appliedAt;

    public Long getId() {
        return id;
    }

    public ShopOrder getOrder() {
        return order;
    }

    public void setOrder(ShopOrder order) {
        this.order = order;
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

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }
}
