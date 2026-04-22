package pl.mindrush.backend.shop;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class ShopOrderEffectHandlerRegistry {

    private final List<ShopOrderEffectHandler> handlers;

    public ShopOrderEffectHandlerRegistry(List<ShopOrderEffectHandler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public ShopOrderEffectHandler resolve(ShopProductPlanEffectType type, String code) {
        String normalizedCode = String.valueOf(code == null ? "" : code).trim().toUpperCase(Locale.ROOT);
        if (type == ShopProductPlanEffectType.DURATION_DAYS && normalizedCode.isEmpty()) {
            normalizedCode = "PREMIUM_ACCESS";
        }
        for (ShopOrderEffectHandler handler : handlers) {
            if (handler.supports(type, normalizedCode)) {
                return handler;
            }
        }
        throw new ResponseStatusException(BAD_REQUEST, "No fulfillment handler for effect " + type + ":" + normalizedCode);
    }
}
