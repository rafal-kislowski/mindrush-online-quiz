CREATE TABLE `shop_products` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kind` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `code` varchar(64) NOT NULL,
  `slug` varchar(120) NOT NULL,
  `title` varchar(120) NOT NULL,
  `subtitle` varchar(220) DEFAULT NULL,
  `description` varchar(2000) DEFAULT NULL,
  `badge_label` varchar(80) DEFAULT NULL,
  `hero_image_url` varchar(500) DEFAULT NULL,
  `checkout_enabled` bit(1) NOT NULL DEFAULT b'1',
  `sort_order` int NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_shop_products_code` (`code`),
  UNIQUE KEY `uq_shop_products_slug` (`slug`),
  KEY `idx_shop_products_status_sort` (`status`,`sort_order`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `shop_product_plans` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `code` varchar(32) NOT NULL,
  `label` varchar(120) NOT NULL,
  `duration_days` int NOT NULL,
  `currency` varchar(3) NOT NULL,
  `gross_amount_minor` int NOT NULL,
  `teaser` varchar(220) DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_shop_product_plans_product_code` (`product_id`,`code`),
  KEY `idx_shop_product_plans_product_sort` (`product_id`,`sort_order`),
  CONSTRAINT `fk_shop_product_plans_product` FOREIGN KEY (`product_id`) REFERENCES `shop_products` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `shop_product_trust_highlights` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `icon` varchar(64) DEFAULT NULL,
  `title` varchar(120) NOT NULL,
  `detail` varchar(220) NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_shop_product_trust_product_sort` (`product_id`,`sort_order`),
  CONSTRAINT `fk_shop_product_trust_product` FOREIGN KEY (`product_id`) REFERENCES `shop_products` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `shop_product_advantages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `icon` varchar(64) DEFAULT NULL,
  `title` varchar(120) NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_shop_product_advantages_product_sort` (`product_id`,`sort_order`),
  CONSTRAINT `fk_shop_product_advantages_product` FOREIGN KEY (`product_id`) REFERENCES `shop_products` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `shop_product_advantage_bullets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `advantage_id` bigint NOT NULL,
  `value` varchar(240) NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_shop_product_adv_bullets_adv_sort` (`advantage_id`,`sort_order`),
  CONSTRAINT `fk_shop_product_adv_bullets_advantage` FOREIGN KEY (`advantage_id`) REFERENCES `shop_product_advantages` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `shop_products` (
  `kind`,
  `status`,
  `code`,
  `slug`,
  `title`,
  `subtitle`,
  `description`,
  `badge_label`,
  `hero_image_url`,
  `checkout_enabled`,
  `sort_order`,
  `created_at`,
  `updated_at`
) VALUES (
  'PREMIUM_ACCESS',
  'ACTIVE',
  'PREMIUM',
  'premium',
  'MindRush Premium',
  'Unlock higher quiz limits, priority creator workflow and premium account styling.',
  'Premium is a timed subscription prepared for a future PayU sandbox checkout flow. In this release the payment step is simulated, but the order lifecycle, premium activation and expiration are already handled in production-grade backend logic.',
  'Premium Access',
  '/shop/shop_premium_account.png',
  b'1',
  0,
  UTC_TIMESTAMP(6),
  UTC_TIMESTAMP(6)
);

SET @premium_product_id = LAST_INSERT_ID();

INSERT INTO `shop_product_trust_highlights` (`product_id`, `icon`, `title`, `detail`, `sort_order`) VALUES
  (@premium_product_id, 'fa-shield-halved', 'Secure checkout', 'Server-validated order flow', 0),
  (@premium_product_id, 'fa-bolt', 'Instant activation', 'Applied right after payment', 1),
  (@premium_product_id, 'fa-hourglass-half', 'Stackable time', 'Each purchase extends expiry', 2),
  (@premium_product_id, 'fa-database', 'No data loss', 'Content stays after expiration', 3);

INSERT INTO `shop_product_plans` (`product_id`, `code`, `label`, `duration_days`, `currency`, `gross_amount_minor`, `teaser`, `sort_order`) VALUES
  (@premium_product_id, 'DAY_1', '1 day', 1, 'PLN', 499, 'Quick access for a single short sprint.', 0),
  (@premium_product_id, 'DAY_3', '3 days', 3, 'PLN', 1199, 'Weekend package for focused creation.', 1),
  (@premium_product_id, 'DAY_7', '7 days', 7, 'PLN', 1999, 'Full week boost for active players.', 2),
  (@premium_product_id, 'DAY_14', '14 days', 14, 'PLN', 2999, 'Balanced plan for longer premium sessions.', 3),
  (@premium_product_id, 'MONTH_1', '1 month', 30, 'PLN', 4999, 'Most practical option for regular usage.', 4),
  (@premium_product_id, 'YEAR_1', '1 year', 365, 'PLN', 34999, 'Best long-term value for dedicated users.', 5);

INSERT INTO `shop_product_advantages` (`product_id`, `icon`, `title`, `sort_order`) VALUES
  (@premium_product_id, 'fa-layer-group', 'Creator Capacity', 0),
  (@premium_product_id, 'fa-list-check', 'Build Bigger Quizzes', 1),
  (@premium_product_id, 'fa-stopwatch', 'Longer Sessions', 2),
  (@premium_product_id, 'fa-certificate', 'Premium Presence', 3),
  (@premium_product_id, 'fa-hourglass-end', 'No Time Loss', 4);

SET @premium_advantage_capacity = (
  SELECT `id` FROM `shop_product_advantages`
  WHERE `product_id` = @premium_product_id AND `sort_order` = 0
  LIMIT 1
);
SET @premium_advantage_build = (
  SELECT `id` FROM `shop_product_advantages`
  WHERE `product_id` = @premium_product_id AND `sort_order` = 1
  LIMIT 1
);
SET @premium_advantage_sessions = (
  SELECT `id` FROM `shop_product_advantages`
  WHERE `product_id` = @premium_product_id AND `sort_order` = 2
  LIMIT 1
);
SET @premium_advantage_presence = (
  SELECT `id` FROM `shop_product_advantages`
  WHERE `product_id` = @premium_product_id AND `sort_order` = 3
  LIMIT 1
);
SET @premium_advantage_stack = (
  SELECT `id` FROM `shop_product_advantages`
  WHERE `product_id` = @premium_product_id AND `sort_order` = 4
  LIMIT 1
);

INSERT INTO `shop_product_advantage_bullets` (`advantage_id`, `value`, `sort_order`) VALUES
  (@premium_advantage_capacity, '60 own quizzes', 0),
  (@premium_advantage_capacity, '20 published quizzes', 1),
  (@premium_advantage_capacity, '10 pending submissions', 2),
  (@premium_advantage_build, 'Up to 100 questions per quiz', 0),
  (@premium_advantage_build, 'Up to 100 question images per quiz', 1),
  (@premium_advantage_sessions, 'Timer up to 300 seconds', 0),
  (@premium_advantage_sessions, 'Matches up to 100 questions', 1),
  (@premium_advantage_presence, 'Premium badge and highlighted nickname across rankings, lobbies and profile', 0),
  (@premium_advantage_stack, 'Each next purchase extends your active premium period', 0);

ALTER TABLE `shop_orders`
  CHANGE COLUMN `product_code` `product_code_snapshot` varchar(64) NOT NULL;

ALTER TABLE `shop_orders`
  ADD COLUMN `product_id` bigint DEFAULT NULL AFTER `user_id`,
  ADD COLUMN `product_slug_snapshot` varchar(120) DEFAULT NULL AFTER `product_code_snapshot`;

UPDATE `shop_orders`
SET `product_code_snapshot` = UPPER(TRIM(`product_code_snapshot`)),
    `product_slug_snapshot` = LOWER(TRIM(`product_code_snapshot`));

UPDATE `shop_orders` o
JOIN `shop_products` p ON p.`code` = o.`product_code_snapshot`
SET o.`product_id` = p.`id`,
    o.`product_slug_snapshot` = p.`slug`;

ALTER TABLE `shop_orders`
  MODIFY COLUMN `product_id` bigint NOT NULL,
  MODIFY COLUMN `product_slug_snapshot` varchar(120) NOT NULL;

ALTER TABLE `shop_orders`
  ADD CONSTRAINT `fk_shop_orders_product` FOREIGN KEY (`product_id`) REFERENCES `shop_products` (`id`);

CREATE INDEX `idx_shop_orders_product_created` ON `shop_orders` (`product_id`, `created_at`);
