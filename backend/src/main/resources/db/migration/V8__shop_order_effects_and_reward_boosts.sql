ALTER TABLE `app_users`
  ADD COLUMN `xp_boost_expires_at` datetime(6) DEFAULT NULL,
  ADD COLUMN `rank_points_boost_expires_at` datetime(6) DEFAULT NULL,
  ADD COLUMN `coins_boost_expires_at` datetime(6) DEFAULT NULL;

CREATE TABLE `shop_order_effects` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `effect_type` varchar(32) NOT NULL,
  `effect_code` varchar(64) DEFAULT NULL,
  `effect_value` int NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  `applied_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_shop_order_effects_order_sort` (`order_id`,`sort_order`),
  KEY `idx_shop_order_effects_order_sort` (`order_id`,`sort_order`),
  CONSTRAINT `fk_shop_order_effects_order` FOREIGN KEY (`order_id`) REFERENCES `shop_orders` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `shop_order_effects` (`order_id`, `effect_type`, `effect_code`, `effect_value`, `sort_order`, `applied_at`)
SELECT
  o.`id`,
  pe.`effect_type`,
  pe.`effect_code`,
  pe.`effect_value`,
  pe.`sort_order`,
  o.`fulfilled_at`
FROM `shop_orders` o
JOIN `shop_product_plans` p
  ON p.`product_id` = o.`product_id`
 AND p.`code` = o.`plan_code`
JOIN `shop_product_plan_effects` pe
  ON pe.`plan_id` = p.`id`;

INSERT INTO `shop_order_effects` (`order_id`, `effect_type`, `effect_code`, `effect_value`, `sort_order`, `applied_at`)
SELECT
  o.`id`,
  'DURATION_DAYS',
  'PREMIUM_ACCESS',
  o.`duration_days`,
  0,
  o.`fulfilled_at`
FROM `shop_orders` o
WHERE o.`duration_days` > 0
  AND NOT EXISTS (
    SELECT 1
    FROM `shop_order_effects` oe
    WHERE oe.`order_id` = o.`id`
  );
