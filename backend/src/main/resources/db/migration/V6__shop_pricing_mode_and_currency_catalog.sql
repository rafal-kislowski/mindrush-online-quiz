ALTER TABLE `shop_products`
  ADD COLUMN `pricing_mode` varchar(32) NOT NULL DEFAULT 'SUBSCRIPTION' AFTER `status`;

ALTER TABLE `shop_product_plans`
  MODIFY COLUMN `currency` varchar(16) NOT NULL,
  ADD COLUMN `grant_value` int DEFAULT NULL AFTER `gross_amount_minor`,
  ADD COLUMN `grant_unit` varchar(32) DEFAULT NULL AFTER `grant_value`;

ALTER TABLE `shop_orders`
  MODIFY COLUMN `currency` varchar(16) NOT NULL;
