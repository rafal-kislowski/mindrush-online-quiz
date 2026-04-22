UPDATE `shop_product_plan_effects`
SET `effect_code` = 'PREMIUM_ACCESS'
WHERE `effect_type` = 'DURATION_DAYS'
  AND (`effect_code` IS NULL OR TRIM(`effect_code`) = '');

UPDATE `shop_order_effects`
SET `effect_code` = 'PREMIUM_ACCESS'
WHERE `effect_type` = 'DURATION_DAYS'
  AND (`effect_code` IS NULL OR TRIM(`effect_code`) = '');
