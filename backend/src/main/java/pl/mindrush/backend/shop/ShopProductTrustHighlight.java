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
        name = "shop_product_trust_highlights",
        indexes = {
                @Index(name = "idx_shop_product_trust_product_sort", columnList = "product_id, sort_order")
        }
)
public class ShopProductTrustHighlight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ShopProduct product;

    @Column(name = "icon", length = 64)
    private String icon;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "detail", nullable = false, length = 220)
    private String detail;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getId() {
        return id;
    }

    public ShopProduct getProduct() {
        return product;
    }

    public void setProduct(ShopProduct product) {
        this.product = product;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
