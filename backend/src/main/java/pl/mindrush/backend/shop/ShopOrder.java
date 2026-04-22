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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "shop_orders",
        indexes = {
                @Index(name = "idx_shop_orders_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_shop_orders_payment_status", columnList = "payment_status")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_shop_order_public_id", columnNames = "public_id")
        }
)
public class ShopOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, length = 36)
    private String publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ShopProduct product;

    @Column(name = "product_code_snapshot", nullable = false, length = 64)
    private String productCodeSnapshot;

    @Column(name = "product_slug_snapshot", nullable = false, length = 120)
    private String productSlugSnapshot;

    @Column(name = "product_name_snapshot", nullable = false, length = 120)
    private String productNameSnapshot;

    @Column(name = "plan_code", nullable = false, length = 32)
    private String planCode;

    @Column(name = "plan_name_snapshot", nullable = false, length = 120)
    private String planNameSnapshot;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "gross_amount_minor", nullable = false)
    private int grossAmountMinor;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_provider", nullable = false, length = 32)
    private ShopPaymentProvider paymentProvider;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 32)
    private ShopPaymentStatus paymentStatus;

    @Column(name = "customer_email", nullable = false, length = 190)
    private String customerEmail;

    @Column(name = "customer_display_name", nullable = false, length = 32)
    private String customerDisplayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "premium_starts_at")
    private Instant premiumStartsAt;

    @Column(name = "premium_expires_at")
    private Instant premiumExpiresAt;

    @Column(name = "payment_reference", length = 128)
    private String paymentReference;

    @Column(name = "provider_order_id", length = 128)
    private String providerOrderId;

    @Column(name = "checkout_redirect_url", length = 500)
    private String checkoutRedirectUrl;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @OneToMany(mappedBy = "order", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ShopOrderEffect> effects = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public ShopProduct getProduct() {
        return product;
    }

    public void setProduct(ShopProduct product) {
        this.product = product;
    }

    public String getProductCodeSnapshot() {
        return productCodeSnapshot;
    }

    public void setProductCodeSnapshot(String productCodeSnapshot) {
        this.productCodeSnapshot = productCodeSnapshot;
    }

    public String getProductSlugSnapshot() {
        return productSlugSnapshot;
    }

    public void setProductSlugSnapshot(String productSlugSnapshot) {
        this.productSlugSnapshot = productSlugSnapshot;
    }

    public String getProductNameSnapshot() {
        return productNameSnapshot;
    }

    public void setProductNameSnapshot(String productNameSnapshot) {
        this.productNameSnapshot = productNameSnapshot;
    }

    public String getPlanCode() {
        return planCode;
    }

    public void setPlanCode(String planCode) {
        this.planCode = planCode;
    }

    public String getPlanNameSnapshot() {
        return planNameSnapshot;
    }

    public void setPlanNameSnapshot(String planNameSnapshot) {
        this.planNameSnapshot = planNameSnapshot;
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

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(int durationDays) {
        this.durationDays = durationDays;
    }

    public ShopPaymentProvider getPaymentProvider() {
        return paymentProvider;
    }

    public void setPaymentProvider(ShopPaymentProvider paymentProvider) {
        this.paymentProvider = paymentProvider;
    }

    public ShopPaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(ShopPaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public String getCustomerDisplayName() {
        return customerDisplayName;
    }

    public void setCustomerDisplayName(String customerDisplayName) {
        this.customerDisplayName = customerDisplayName;
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

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public void setFulfilledAt(Instant fulfilledAt) {
        this.fulfilledAt = fulfilledAt;
    }

    public Instant getPremiumStartsAt() {
        return premiumStartsAt;
    }

    public void setPremiumStartsAt(Instant premiumStartsAt) {
        this.premiumStartsAt = premiumStartsAt;
    }

    public Instant getPremiumExpiresAt() {
        return premiumExpiresAt;
    }

    public void setPremiumExpiresAt(Instant premiumExpiresAt) {
        this.premiumExpiresAt = premiumExpiresAt;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public void setProviderOrderId(String providerOrderId) {
        this.providerOrderId = providerOrderId;
    }

    public String getCheckoutRedirectUrl() {
        return checkoutRedirectUrl;
    }

    public void setCheckoutRedirectUrl(String checkoutRedirectUrl) {
        this.checkoutRedirectUrl = checkoutRedirectUrl;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public List<ShopOrderEffect> getEffects() {
        return effects;
    }

    public void setEffects(List<ShopOrderEffect> effects) {
        this.effects = effects == null ? new ArrayList<>() : effects;
    }
}
