-- Flux Panel 1.7.0 增量迁移（在每个面板数据库中执行一次）
-- 建议先备份数据库。

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'mode'
    ),
    'ALTER TABLE `forward` ADD COLUMN `mode` VARCHAR(20) NOT NULL DEFAULT ''direct''',
    'SELECT "mode exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'chain_strategy'
    ),
    'ALTER TABLE `forward` ADD COLUMN `chain_strategy` VARCHAR(20) NOT NULL DEFAULT ''smart''',
    'SELECT "chain_strategy exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'chain_hops'
    ),
    'ALTER TABLE `forward` ADD COLUMN `chain_hops` INT(10) NOT NULL DEFAULT 0',
    'SELECT "chain_hops exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'tunnel_ids'
    ),
    'ALTER TABLE `forward` ADD COLUMN `tunnel_ids` VARCHAR(1000) DEFAULT NULL',
    'SELECT "tunnel_ids exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'bandwidth_mode'
    ),
    'ALTER TABLE `forward` ADD COLUMN `bandwidth_mode` VARCHAR(20) NOT NULL DEFAULT ''none''',
    'SELECT "bandwidth_mode exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'bandwidth_up'
    ),
    'ALTER TABLE `forward` ADD COLUMN `bandwidth_up` BIGINT(20) NOT NULL DEFAULT 0',
    'SELECT "bandwidth_up exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'bandwidth_down'
    ),
    'ALTER TABLE `forward` ADD COLUMN `bandwidth_down` BIGINT(20) NOT NULL DEFAULT 0',
    'SELECT "bandwidth_down exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'bandwidth_combined'
    ),
    'ALTER TABLE `forward` ADD COLUMN `bandwidth_combined` BIGINT(20) NOT NULL DEFAULT 0',
    'SELECT "bandwidth_combined exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'max_source_ips'
    ),
    'ALTER TABLE `forward` ADD COLUMN `max_source_ips` INT(10) NOT NULL DEFAULT 0',
    'SELECT "max_source_ips exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'max_conn_per_ip'
    ),
    'ALTER TABLE `forward` ADD COLUMN `max_conn_per_ip` INT(10) NOT NULL DEFAULT 0',
    'SELECT "max_conn_per_ip exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'expire_at'
    ),
    'ALTER TABLE `forward` ADD COLUMN `expire_at` BIGINT(20) NOT NULL DEFAULT 0',
    'SELECT "expire_at exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE `tunnel`
  MODIFY COLUMN `in_node_id` INT(10) DEFAULT NULL,
  MODIFY COLUMN `in_ip` VARCHAR(100) DEFAULT NULL,
  MODIFY COLUMN `out_node_id` INT(10) DEFAULT NULL,
  MODIFY COLUMN `out_ip` VARCHAR(100) DEFAULT NULL;
