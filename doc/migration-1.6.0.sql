-- Flux Panel 1.6.0 增量迁移（MySQL 5.7，每个数据库仅执行一次）
ALTER TABLE `forward`
  ADD COLUMN `target_weights` varchar(1000) DEFAULT NULL AFTER `probe_message`;

ALTER TABLE `tunnel`
  ADD COLUMN `out_node_ids` varchar(1000) DEFAULT NULL AFTER `interface_name`,
  ADD COLUMN `out_node_weights` varchar(1000) DEFAULT NULL AFTER `out_node_ids`,
  ADD COLUMN `chain_node_ids` varchar(1000) DEFAULT NULL AFTER `out_node_weights`,
  ADD COLUMN `balance_strategy` varchar(20) NOT NULL DEFAULT 'fifo' AFTER `chain_node_ids`,
  ADD COLUMN `max_fails` int(10) NOT NULL DEFAULT 1 AFTER `balance_strategy`,
  ADD COLUMN `fail_timeout` int(10) NOT NULL DEFAULT 30 AFTER `max_fails`;

UPDATE `forward`
SET `target_weights` = TRIM(BOTH ',' FROM REPEAT('1,', 1 + LENGTH(`remote_addr`) - LENGTH(REPLACE(`remote_addr`, ',', ''))))
WHERE `target_weights` IS NULL;

UPDATE `tunnel`
SET `out_node_ids` = CAST(`out_node_id` AS CHAR),
    `out_node_weights` = '1',
    `chain_node_ids` = '',
    `balance_strategy` = 'fifo',
    `max_fails` = 1,
    `fail_timeout` = 30
WHERE `out_node_ids` IS NULL;
