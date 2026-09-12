-- 1.7.1 migration: PROXY Protocol v1 forwarding support

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = 'proxy_protocol'
    ),
    'ALTER TABLE `forward` ADD COLUMN `proxy_protocol` TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT "proxy_protocol exists";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
