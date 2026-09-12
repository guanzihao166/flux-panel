-- 1.7.6 migration: entry node outbound IPv4/IPv6 mode.
-- This setting controls only the first outbound hop of a tunnel, never its listener.

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'tunnel' AND column_name = 'entry_ip_mode'
    ),
    'ALTER TABLE `tunnel` ADD COLUMN `entry_ip_mode` VARCHAR(20) NOT NULL DEFAULT ''auto'' AFTER `node_ip_modes`',
    'SELECT "entry_ip_mode exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
