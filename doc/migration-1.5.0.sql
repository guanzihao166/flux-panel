-- Flux Panel 1.5.0 增量迁移（MySQL 5.7）
-- 执行前必须备份。该脚本适用于每个数据库仅执行一次。

ALTER TABLE `forward`
  ADD COLUMN `latency_ms` double DEFAULT NULL AFTER `inx`,
  ADD COLUMN `probe_status` int(10) NOT NULL DEFAULT 0 AFTER `latency_ms`,
  ADD COLUMN `probe_time` bigint(20) DEFAULT NULL AFTER `probe_status`,
  ADD COLUMN `probe_message` varchar(255) DEFAULT NULL AFTER `probe_time`;

CREATE TABLE IF NOT EXISTS `announcement` (
  `id` int(10) NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL,
  `content` longtext NOT NULL,
  `status` int(10) NOT NULL DEFAULT 0,
  `published_time` bigint(20) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_announcement_status_published` (`status`,`published_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `announcement_dismissal` (
  `id` int(10) NOT NULL AUTO_INCREMENT,
  `announcement_id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_announcement_user` (`announcement_id`,`user_id`),
  KEY `idx_announcement_dismissal_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `vite_config` (`name`, `value`, `time`)
SELECT 'turnstile_enabled', 'true', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
WHERE NOT EXISTS (SELECT 1 FROM `vite_config` WHERE `name`='turnstile_enabled');

INSERT INTO `vite_config` (`name`, `value`, `time`)
SELECT 'turnstile_site_key', '', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
WHERE NOT EXISTS (SELECT 1 FROM `vite_config` WHERE `name`='turnstile_site_key');

INSERT INTO `vite_config` (`name`, `value`, `time`)
SELECT 'turnstile_secret_key', '', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
WHERE NOT EXISTS (SELECT 1 FROM `vite_config` WHERE `name`='turnstile_secret_key');
