ALTER TABLE `app_users`
  ADD COLUMN `premium_activated_at` datetime(6) DEFAULT NULL,
  ADD COLUMN `premium_expires_at` datetime(6) DEFAULT NULL;

UPDATE `app_users` u
JOIN `app_user_roles` r ON r.`user_id` = u.`id` AND r.`role` = 'PREMIUM'
SET u.`premium_activated_at` = COALESCE(u.`premium_activated_at`, u.`created_at`),
    u.`premium_expires_at` = COALESCE(u.`premium_expires_at`, DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 3650 DAY));

CREATE TABLE `shop_orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` varchar(36) NOT NULL,
  `user_id` bigint NOT NULL,
  `product_code` enum('PREMIUM') NOT NULL,
  `product_name_snapshot` varchar(120) NOT NULL,
  `plan_code` varchar(32) NOT NULL,
  `plan_name_snapshot` varchar(120) NOT NULL,
  `currency` varchar(3) NOT NULL,
  `gross_amount_minor` int NOT NULL,
  `duration_days` int NOT NULL,
  `payment_provider` enum('SIMULATED','PAYU_SANDBOX') NOT NULL,
  `payment_status` enum('PENDING','PAID','FAILED','CANCELLED') NOT NULL,
  `customer_email` varchar(190) NOT NULL,
  `customer_display_name` varchar(32) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `paid_at` datetime(6) DEFAULT NULL,
  `fulfilled_at` datetime(6) DEFAULT NULL,
  `premium_starts_at` datetime(6) DEFAULT NULL,
  `premium_expires_at` datetime(6) DEFAULT NULL,
  `payment_reference` varchar(128) DEFAULT NULL,
  `provider_order_id` varchar(128) DEFAULT NULL,
  `checkout_redirect_url` varchar(500) DEFAULT NULL,
  `failure_reason` varchar(300) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_shop_order_public_id` (`public_id`),
  KEY `idx_shop_orders_user_created` (`user_id`,`created_at`),
  KEY `idx_shop_orders_payment_status` (`payment_status`),
  CONSTRAINT `fk_shop_orders_user` FOREIGN KEY (`user_id`) REFERENCES `app_users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
