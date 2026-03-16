-- Baseline schema generated from current MySQL structure (no data).
SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;

CREATE TABLE `app_user_roles` (
  `user_id` bigint NOT NULL,
  `role` enum('ADMIN','BANNED','PREMIUM','USER') NOT NULL,
  PRIMARY KEY (`user_id`,`role`),
  CONSTRAINT `FKjhld72ac4tj54kp371ixd1klk` FOREIGN KEY (`user_id`) REFERENCES `app_users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `app_users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `coins` int NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  `display_name` varchar(32) DEFAULT NULL,
  `email` varchar(190) NOT NULL,
  `email_verified` tinyint(1) NOT NULL DEFAULT '1',
  `email_verified_at` datetime(6) DEFAULT NULL,
  `last_display_name_change_at` datetime(6) DEFAULT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `last_password_reset_email_sent_at` datetime(6) DEFAULT NULL,
  `last_verification_email_sent_at` datetime(6) DEFAULT NULL,
  `password_hash` varchar(100) NOT NULL,
  `rank_points` int NOT NULL DEFAULT '0',
  `xp` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_app_user_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `auth_action_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `consumed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  `token_type` enum('EMAIL_VERIFY','PASSWORD_RESET') NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_auth_action_token_hash` (`token_hash`),
  KEY `idx_auth_action_tokens_user_type` (`user_id`,`token_type`,`consumed_at`),
  KEY `idx_auth_action_tokens_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `casual_three_lives_records` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `best_answered` int NOT NULL DEFAULT '0',
  `best_duration_ms` bigint NOT NULL DEFAULT '0',
  `best_points` int NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  `guest_session_id` varchar(36) NOT NULL,
  `participant_key` varchar(128) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_casual_three_lives_participant` (`participant_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `game_answers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `answer_time_ms` int NOT NULL DEFAULT '0',
  `answered_at` datetime(6) NOT NULL,
  `correct` bit(1) NOT NULL,
  `guest_session_id` varchar(36) NOT NULL,
  `points` int NOT NULL DEFAULT '0',
  `question_id` bigint NOT NULL,
  `selected_option_id` bigint DEFAULT NULL,
  `game_session_id` varchar(36) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_game_answer_once` (`game_session_id`,`question_id`,`guest_session_id`),
  CONSTRAINT `FKrfe0m3a441h5071iky5h5qsx8` FOREIGN KEY (`game_session_id`) REFERENCES `game_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `game_players` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `display_name` varchar(32) NOT NULL,
  `guest_session_id` varchar(36) NOT NULL,
  `order_index` int NOT NULL,
  `game_session_id` varchar(36) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_game_player_once` (`game_session_id`,`guest_session_id`),
  CONSTRAINT `FKq2tnojfxbjyrq32nohh08ox0e` FOREIGN KEY (`game_session_id`) REFERENCES `game_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `game_session_questions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `game_session_id` varchar(36) NOT NULL,
  `order_index` int NOT NULL,
  `question_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_game_session_questions_order` (`game_session_id`,`order_index`),
  KEY `idx_game_session_questions_session` (`game_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `game_sessions` (
  `id` varchar(36) NOT NULL,
  `coins_rewards_enabled` bit(1) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `current_question_index` int NOT NULL,
  `ended_at` datetime(6) DEFAULT NULL,
  `finish_reason` enum('COMPLETED','EXPIRED','MANUAL_END') DEFAULT NULL,
  `last_activity_at` datetime(6) DEFAULT NULL,
  `lives_remaining` int DEFAULT NULL,
  `lobby_id` varchar(36) NOT NULL,
  `mode` enum('STANDARD','THREE_LIVES','TRAINING') NOT NULL,
  `question_duration_ms` int DEFAULT NULL,
  `question_pool_category_id` bigint DEFAULT NULL,
  `quiz_id` bigint NOT NULL,
  `rank_points_rewards_enabled` bit(1) DEFAULT NULL,
  `rewards_applied` tinyint(1) NOT NULL DEFAULT '0',
  `rewards_applied_at` datetime(6) DEFAULT NULL,
  `stage` enum('PRE_COUNTDOWN','QUESTION','REVEAL') NOT NULL,
  `stage_ends_at` datetime(6) DEFAULT NULL,
  `started_at` datetime(6) NOT NULL,
  `status` enum('FINISHED','IN_PROGRESS') NOT NULL,
  `xp_rewards_enabled` bit(1) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `guest_sessions` (
  `id` varchar(36) NOT NULL,
  `coins` int NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  `display_name` varchar(32) DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `last_seen_at` datetime(6) NOT NULL,
  `rank_points` int NOT NULL DEFAULT '0',
  `revoked` bit(1) NOT NULL,
  `user_id` bigint DEFAULT NULL,
  `xp` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `lobbies` (
  `id` varchar(36) NOT NULL,
  `code` varchar(6) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `empty_since` datetime(6) DEFAULT NULL,
  `max_players` int NOT NULL,
  `owner_authenticated` bit(1) NOT NULL,
  `owner_guest_session_id` varchar(36) NOT NULL,
  `password_hash` varchar(255) DEFAULT NULL,
  `pin_code` varchar(8) DEFAULT NULL,
  `ranking_enabled` bit(1) NOT NULL,
  `selected_quiz_id` bigint DEFAULT NULL,
  `status` enum('CLOSED','IN_GAME','OPEN') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKhwbhkgfymnubeije1lh2kus95` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `lobby_bans` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `guest_session_id` varchar(36) DEFAULT NULL,
  `lobby_id` varchar(36) NOT NULL,
  `user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_lobby_ban_session` (`lobby_id`,`guest_session_id`),
  UNIQUE KEY `uq_lobby_ban_user` (`lobby_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `lobby_participants` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `display_name` varchar(32) NOT NULL,
  `guest_session_id` varchar(36) NOT NULL,
  `joined_at` datetime(6) NOT NULL,
  `ready` bit(1) DEFAULT NULL,
  `lobby_id` varchar(36) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_lobby_participant_guest` (`lobby_id`,`guest_session_id`),
  CONSTRAINT `FK89cip7mdg24cqbbnn8ptt4ue2` FOREIGN KEY (`lobby_id`) REFERENCES `lobbies` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `quiz_answer_options` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `correct` bit(1) NOT NULL,
  `image_url` varchar(500) DEFAULT NULL,
  `order_index` int NOT NULL,
  `text` varchar(200) NOT NULL,
  `question_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKtmdyval9xaccpei272f23cpwv` (`question_id`),
  CONSTRAINT `FKtmdyval9xaccpei272f23cpwv` FOREIGN KEY (`question_id`) REFERENCES `quiz_questions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `quiz_categories` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_quiz_category_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `quiz_favorites` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `quiz_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_quiz_favorite_user_quiz` (`user_id`,`quiz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `quiz_moderation_issues` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message` varchar(500) NOT NULL,
  `question_id` bigint NOT NULL,
  `quiz_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKem33jmqutw15nwc8m5qdq9wyp` (`quiz_id`),
  CONSTRAINT `FKem33jmqutw15nwc8m5qdq9wyp` FOREIGN KEY (`quiz_id`) REFERENCES `quizzes` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `quiz_questions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `image_url` varchar(500) DEFAULT NULL,
  `order_index` int NOT NULL,
  `prompt` varchar(500) NOT NULL,
  `quiz_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKanfmgf6ksbdnv7ojb0pfve54q` (`quiz_id`),
  CONSTRAINT `FKanfmgf6ksbdnv7ojb0pfve54q` FOREIGN KEY (`quiz_id`) REFERENCES `quizzes` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `quizzes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `avatar_bg_end` varchar(32) DEFAULT NULL,
  `avatar_bg_start` varchar(32) DEFAULT NULL,
  `avatar_image_url` varchar(500) DEFAULT NULL,
  `avatar_text_color` varchar(32) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL,
  `game_mode` enum('CASUAL','RANKED') DEFAULT NULL,
  `include_in_ranking` bit(1) DEFAULT NULL,
  `moderation_reason` varchar(500) DEFAULT NULL,
  `moderation_status` enum('APPROVED','NONE','PENDING','REJECTED') DEFAULT NULL,
  `moderation_updated_at` datetime(6) DEFAULT NULL,
  `owner_user_id` bigint DEFAULT NULL,
  `question_time_limit_seconds` int DEFAULT NULL,
  `questions_per_game` int DEFAULT NULL,
  `quiz_source` enum('CUSTOM','OFFICIAL') DEFAULT NULL,
  `status` enum('ACTIVE','DRAFT','TRASHED') DEFAULT NULL,
  `title` varchar(120) NOT NULL,
  `version` bigint DEFAULT NULL,
  `xp_enabled` bit(1) DEFAULT NULL,
  `category_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKqyp5vbqc9vy2laiodk3gmtrnx` (`category_id`),
  CONSTRAINT `FKqyp5vbqc9vy2laiodk3gmtrnx` FOREIGN KEY (`category_id`) REFERENCES `quiz_categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `refresh_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `revoked` bit(1) NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_refresh_token_hash` (`token_hash`),
  KEY `FKt72gijoak5yufxws745gwflis` (`user_id`),
  CONSTRAINT `FKt72gijoak5yufxws745gwflis` FOREIGN KEY (`user_id`) REFERENCES `app_users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_achievement_unlocks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `achievement_key` varchar(64) NOT NULL,
  `unlocked_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_achievement_unlock` (`user_id`,`achievement_key`),
  KEY `idx_user_achievement_unlocks_user` (`user_id`,`unlocked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_notifications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `avatar_bg_end` varchar(32) DEFAULT NULL,
  `avatar_bg_start` varchar(32) DEFAULT NULL,
  `avatar_image_url` varchar(500) DEFAULT NULL,
  `avatar_text_color` varchar(32) DEFAULT NULL,
  `category` enum('GIFT','MODERATION','NEWS','SYSTEM') NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `decision` enum('APPROVED','REJECTED') DEFAULT NULL,
  `dedupe_key` varchar(190) DEFAULT NULL,
  `dismissed_at` datetime(6) DEFAULT NULL,
  `meta` varchar(200) DEFAULT NULL,
  `payload_json` tinytext,
  `read_at` datetime(6) DEFAULT NULL,
  `route_path` varchar(255) DEFAULT NULL,
  `route_query_json` tinytext,
  `severity` enum('DANGER','NEUTRAL','SUCCESS','WARNING') NOT NULL,
  `subtitle` varchar(220) DEFAULT NULL,
  `text` varchar(500) DEFAULT NULL,
  `title` varchar(160) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_notification_dedupe` (`user_id`,`dedupe_key`),
  KEY `idx_user_notifications_user_created` (`user_id`,`created_at`),
  KEY `idx_user_notifications_user_unread` (`user_id`,`read_at`,`dismissed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET UNIQUE_CHECKS = 1;
SET FOREIGN_KEY_CHECKS = 1;
