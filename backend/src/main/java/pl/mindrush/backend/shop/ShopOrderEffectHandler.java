package pl.mindrush.backend.shop;

public interface ShopOrderEffectHandler {
    boolean supports(ShopProductPlanEffectType type, String code);
    void apply(ShopOrderEffectExecutionContext context, ShopOrderEffect effect);
}
