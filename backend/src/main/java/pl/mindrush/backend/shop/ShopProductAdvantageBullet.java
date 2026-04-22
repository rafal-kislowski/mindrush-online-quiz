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
import jakarta.persistence.Table;

@Entity
@Table(
        name = "shop_product_advantage_bullets",
        indexes = {
                @Index(name = "idx_shop_product_adv_bullets_adv_sort", columnList = "advantage_id, sort_order")
        }
)
public class ShopProductAdvantageBullet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advantage_id", nullable = false)
    private ShopProductAdvantage advantage;

    @Column(name = "bullet_value", nullable = false, length = 240)
    private String value;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getId() {
        return id;
    }

    public ShopProductAdvantage getAdvantage() {
        return advantage;
    }

    public void setAdvantage(ShopProductAdvantage advantage) {
        this.advantage = advantage;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
