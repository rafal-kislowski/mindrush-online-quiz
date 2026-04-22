CREATE TABLE `shop_product_plan_effects` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plan_id` bigint NOT NULL,
  `effect_type` varchar(32) NOT NULL,
  `effect_code` varchar(64) DEFAULT NULL,
  `effect_value` int NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_shop_plan_effects_plan_sort` (`plan_id`, `sort_order`),
  CONSTRAINT `fk_shop_plan_effects_plan` FOREIGN KEY (`plan_id`) REFERENCES `shop_product_plans` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `shop_product_plan_effects` (`plan_id`, `effect_type`, `effect_code`, `effect_value`, `sort_order`)
SELECT p.`id`, 'DURATION_DAYS', NULL, p.`duration_days`, 0
FROM `shop_product_plans` p
WHERE p.`duration_days` > 0;

INSERT INTO `shop_product_plan_effects` (`plan_id`, `effect_type`, `effect_code`, `effect_value`, `sort_order`)
SELECT
  p.`id`,
  'RESOURCE_GRANT',
  UPPER(TRIM(p.`grant_unit`)),
  p.`grant_value`,
  CASE WHEN p.`duration_days` > 0 THEN 1 ELSE 0 END
FROM `shop_product_plans` p
WHERE p.`grant_value` IS NOT NULL
  AND p.`grant_value` > 0
  AND p.`grant_unit` IS NOT NULL
  AND TRIM(p.`grant_unit`) <> '';
