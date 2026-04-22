package pl.mindrush.backend.shop;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.mindrush.backend.JwtCookieAuthenticationFilter;

import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/shop")
public class ShopController {

    private final ShopCatalogService catalogService;
    private final ShopCheckoutService checkoutService;

    public ShopController(
            ShopCatalogService catalogService,
            ShopCheckoutService checkoutService
    ) {
        this.catalogService = catalogService;
        this.checkoutService = checkoutService;
    }

    @GetMapping("/catalog")
    public ShopCatalogService.CatalogResponse catalog() {
        return catalogService.getCatalog();
    }

    @GetMapping("/products/{slug}")
    public ShopCatalogService.ProductView product(@PathVariable String slug) {
        return catalogService.getProductBySlug(slug);
    }

    @GetMapping("/orders")
    public List<ShopCheckoutService.OrderView> orders(Authentication authentication) {
        return checkoutService.listOrders(requireAuthenticatedUserId(authentication));
    }

    @PostMapping("/orders")
    public ResponseEntity<ShopCheckoutService.OrderView> createOrder(
            Authentication authentication,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        ShopCheckoutService.OrderView order = checkoutService.createOrder(
                requireAuthenticatedUserId(authentication),
                request.productSlug(),
                request.planCode(),
                request.quantity() == null ? 1 : request.quantity()
        );
        return ResponseEntity.status(CREATED).body(order);
    }

    @PostMapping("/orders/batch")
    public ResponseEntity<List<ShopCheckoutService.OrderView>> createOrdersBatch(
            Authentication authentication,
            @Valid @RequestBody CreateOrdersBatchRequest request
    ) {
        List<ShopCheckoutService.CreateOrderLineInput> items = request.items().stream()
                .map(item -> new ShopCheckoutService.CreateOrderLineInput(
                        item.productSlug(),
                        item.planCode(),
                        item.quantity() == null ? 1 : item.quantity()
                ))
                .toList();
        List<ShopCheckoutService.OrderView> orders = checkoutService.createOrders(
                requireAuthenticatedUserId(authentication),
                items
        );
        return ResponseEntity.status(CREATED).body(orders);
    }

    @PostMapping("/orders/{publicId}/simulate-payment")
    public ShopCheckoutService.OrderView simulatePayment(
            Authentication authentication,
            @PathVariable String publicId,
            @Valid @RequestBody SimulatePaymentRequest request
    ) {
        ShopCheckoutService.SimulationOutcome outcome = parseOutcome(request.outcome());
        return checkoutService.simulatePayment(requireAuthenticatedUserId(authentication), publicId, outcome);
    }

    private long requireAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication is required");
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof JwtCookieAuthenticationFilter.AuthenticatedUser au)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication is required");
        }
        return au.id();
    }

    private static ShopCheckoutService.SimulationOutcome parseOutcome(String rawOutcome) {
        String normalized = String.valueOf(rawOutcome == null ? "" : rawOutcome).trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return ShopCheckoutService.SimulationOutcome.SUCCESS;
        }
        try {
            return ShopCheckoutService.SimulationOutcome.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported simulation outcome");
        }
    }

    public record CreateOrderRequest(
            @NotBlank(message = "Product slug is required")
            @Size(max = 32, message = "Product slug is too long")
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Product slug is invalid")
            String productSlug,
            @NotBlank(message = "Plan code is required")
            @Size(max = 32, message = "Plan code is too long")
            @Pattern(regexp = "^[A-Z0-9_]+$", message = "Plan code is invalid")
            String planCode,
            @Min(value = 1, message = "Quantity must be at least 1")
            @Max(value = 1000, message = "Quantity is too high")
            Integer quantity
    ) {}

    public record SimulatePaymentRequest(
            @Size(max = 16, message = "Outcome is too long")
            String outcome
    ) {}

    public record CreateOrdersBatchRequest(
            @jakarta.validation.constraints.NotEmpty(message = "At least one order item is required")
            @Size(max = 100, message = "Too many order items")
            List<@Valid CreateOrderItemRequest> items
    ) {}

    public record CreateOrderItemRequest(
            @NotBlank(message = "Product slug is required")
            @Size(max = 32, message = "Product slug is too long")
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Product slug is invalid")
            String productSlug,
            @NotBlank(message = "Plan code is required")
            @Size(max = 32, message = "Plan code is too long")
            @Pattern(regexp = "^[A-Z0-9_]+$", message = "Plan code is invalid")
            String planCode,
            @Min(value = 1, message = "Quantity must be at least 1")
            @Max(value = 1000, message = "Quantity is too high")
            Integer quantity
    ) {}
}
