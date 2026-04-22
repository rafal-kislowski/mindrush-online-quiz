package pl.mindrush.backend.shop;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class ShopEffectCatalogService {

    private static final List<EffectDefinition> DEFINITIONS = List.of(
            new EffectDefinition(ShopProductPlanEffectType.DURATION_DAYS, "PREMIUM_ACCESS", "Premium access", "Extends premium account access"),
            new EffectDefinition(ShopProductPlanEffectType.DURATION_DAYS, "XP_BOOST", "XP boost", "Enables XP boost for a duration"),
            new EffectDefinition(ShopProductPlanEffectType.DURATION_DAYS, "RP_BOOST", "Rank points boost", "Enables rank points boost for a duration"),
            new EffectDefinition(ShopProductPlanEffectType.DURATION_DAYS, "COINS_BOOST", "Coins boost", "Enables coins boost for a duration"),
            new EffectDefinition(ShopProductPlanEffectType.RESOURCE_GRANT, "COINS", "Coins", "Grants coins immediately"),
            new EffectDefinition(ShopProductPlanEffectType.RESOURCE_GRANT, "XP", "XP", "Grants XP immediately"),
            new EffectDefinition(ShopProductPlanEffectType.RESOURCE_GRANT, "RANK_POINTS", "Rank points", "Grants rank points immediately")
    );

    private final Map<String, EffectDefinition> byKey;

    public ShopEffectCatalogService() {
        Map<String, EffectDefinition> map = new LinkedHashMap<>();
        for (EffectDefinition definition : DEFINITIONS) {
            map.put(key(definition.type(), definition.code()), definition);
        }
        this.byKey = Map.copyOf(map);
    }

    public List<EffectDefinition> all() {
        return DEFINITIONS;
    }

    public List<EffectDefinition> byType(ShopProductPlanEffectType type) {
        if (type == null) return List.of();
        List<EffectDefinition> out = new ArrayList<>();
        for (EffectDefinition definition : DEFINITIONS) {
            if (definition.type() == type) {
                out.add(definition);
            }
        }
        return out;
    }

    public String normalizeAndValidateCode(ShopProductPlanEffectType type, String rawCode, boolean required) {
        String normalized = String.valueOf(rawCode == null ? "" : rawCode).trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            if (required) {
                throw new ResponseStatusException(BAD_REQUEST, "Plan effect code is required");
            }
            if (type == ShopProductPlanEffectType.DURATION_DAYS) {
                normalized = "PREMIUM_ACCESS";
            } else {
                return null;
            }
        }
        EffectDefinition definition = byKey.get(key(type, normalized));
        if (definition == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Plan effect code is not supported");
        }
        return definition.code();
    }

    public EffectDefinition require(ShopProductPlanEffectType type, String rawCode) {
        String code = normalizeAndValidateCode(type, rawCode, true);
        EffectDefinition definition = byKey.get(key(type, code));
        if (definition == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Plan effect code is not supported");
        }
        return definition;
    }

    private static String key(ShopProductPlanEffectType type, String code) {
        return String.valueOf(type == null ? "" : type.name()) + ":" + String.valueOf(code == null ? "" : code);
    }

    public record EffectDefinition(
            ShopProductPlanEffectType type,
            String code,
            String label,
            String description
    ) {}
}
