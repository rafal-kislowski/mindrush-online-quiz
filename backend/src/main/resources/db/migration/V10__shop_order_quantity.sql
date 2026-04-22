ALTER TABLE `shop_orders`
  ADD COLUMN `quantity` int NOT NULL DEFAULT 1 AFTER `gross_amount_minor`;

