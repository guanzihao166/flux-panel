-- 1.7.2 migration: optional node IPv4/IPv6 addresses and per-route IP mode
-- Run once in each Flux Panel database. Back up before applying.

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'node' AND column_name = 'server_ip4'
    ),
    'ALTER TABLE `node` ADD COLUMN `server_ip4` VARCHAR(100) DEFAULT NULL AFTER `server_ip`',
    'SELECT "server_ip4 exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'node' AND column_name = 'server_ip6'
    ),
    'ALTER TABLE `node` ADD COLUMN `server_ip6` VARCHAR(255) DEFAULT NULL AFTER `server_ip4`',
    'SELECT "server_ip6 exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Preserve existing node communication addresses as the matching IP family.
UPDATE `node`
SET `server_ip4` = `server_ip`
WHERE (`server_ip4` IS NULL OR `server_ip4` = '')
  AND `server_ip` REGEXP '^[0-9]{1,3}(\\.[0-9]{1,3}){3}$';

UPDATE `node`
SET `server_ip6` = `server_ip`
WHERE (`server_ip6` IS NULL OR `server_ip6` = '')
  AND `server_ip` LIKE '%:%';

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'tunnel' AND column_name = 'node_ip_modes'
    ),
    'ALTER TABLE `tunnel` ADD COLUMN `node_ip_modes` VARCHAR(2000) DEFAULT NULL AFTER `chain_node_ids`',
    'SELECT "node_ip_modes exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
