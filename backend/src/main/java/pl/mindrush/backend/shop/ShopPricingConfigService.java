package pl.mindrush.backend.shop;

import org.springframework.stereotype.Service;
import pl.mindrush.backend.config.AppShopProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ShopPricingConfigService {

    private final AppShopProperties properties;

    public ShopPricingConfigService(AppShopProperties properties) {
        this.properties = properties;
    }

    public List<PricingCurrencyView> pricingCurrencies() {
        Map<String, PricingCurrencyView> deduped = new LinkedHashMap<>();
        for (AppShopProperties.PricingCurrency item : properties.getPricingCurrencies()) {
            if (item == null) continue;
            String code = normalizeCode(item.getCode());
            if (code.isEmpty()) continue;
            String label = normalizeLabel(item.getLabel(), code);
            String type = normalizeType(item.getType());
            int fractionDigits = Math.max(0, Math.min(6, item.getFractionDigits()));
            deduped.putIfAbsent(code, new PricingCurrencyView(code, label, type, fractionDigits));
        }
        if (deduped.isEmpty()) {
            deduped.put("PLN", new PricingCurrencyView("PLN", "Polish zloty", "FIAT", 2));
            deduped.put("EUR", new PricingCurrencyView("EUR", "Euro", "FIAT", 2));
            deduped.put("USD", new PricingCurrencyView("USD", "US dollar", "FIAT", 2));
            deduped.put("COINS", new PricingCurrencyView("COINS", "Coins", "GAME", 0));
        }
        return List.copyOf(deduped.values());
    }

    public boolean isAllowedCurrency(String rawCode) {
        String code = normalizeCode(rawCode);
        if (code.isEmpty()) return false;
        return pricingCurrencies().stream().anyMatch(item -> item.code().equals(code));
    }

    private static String normalizeCode(String raw) {
        return String.valueOf(raw == null ? "" : raw).trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeLabel(String raw, String fallbackCode) {
        String normalized = String.valueOf(raw == null ? "" : raw).trim();
        return normalized.isEmpty() ? fallbackCode : normalized;
    }

    private static String normalizeType(String raw) {
        String normalized = String.valueOf(raw == null ? "" : raw).trim().toUpperCase(Locale.ROOT);
        return "GAME".equals(normalized) ? "GAME" : "FIAT";
    }

    public record PricingCurrencyView(
            String code,
            String label,
            String type,
            int fractionDigits
    ) {}
}
