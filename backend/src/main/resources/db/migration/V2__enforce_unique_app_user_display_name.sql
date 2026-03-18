UPDATE `app_users`
SET `display_name` = NULL
WHERE `display_name` IS NOT NULL
  AND TRIM(`display_name`) = '';

UPDATE `app_users`
SET `display_name` = REGEXP_REPLACE(TRIM(`display_name`), '[[:space:]]+', ' ')
WHERE `display_name` IS NOT NULL
  AND TRIM(`display_name`) <> '';

ALTER TABLE `app_users`
  ADD CONSTRAINT `uq_app_user_display_name` UNIQUE (`display_name`);
