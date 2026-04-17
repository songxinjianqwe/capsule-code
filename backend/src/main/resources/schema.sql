-- Spring Boot spring.sql.init.mode=always 会自动执行本文件
-- H2 MODE=MySQL 兼容 MyBatis 里的 MySQL 方言
-- 若需要修改建表语句，改完删除 capsule_code/data/capsule_code.mv.db 重启后端即可重建

CREATE TABLE IF NOT EXISTS `claude_conversations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `device_id` varchar(64) NOT NULL,
  `conv_id` varchar(36) NOT NULL,
  `name` varchar(128) NOT NULL,
  `claude_session_id` varchar(128) DEFAULT NULL,
  `created_at` bigint NOT NULL,
  `last_active_at` bigint NOT NULL,
  `is_processing` tinyint(1) NOT NULL DEFAULT '0',
  `enterprise_mode` tinyint(1) NOT NULL DEFAULT '0' COMMENT '进程启动时是否企业版模式',
  `account_mode` varchar(20) NOT NULL DEFAULT 'max' COMMENT '账号模式: max/pro/enterprise',
  PRIMARY KEY (`id`),
  UNIQUE KEY `conv_id` (`conv_id`),
  KEY `idx_device_active` (`device_id`,`last_active_at`)
);
CREATE TABLE IF NOT EXISTS `claude_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conv_id` varchar(36) NOT NULL,
  `role` varchar(16) NOT NULL,
  `subtype` varchar(32) DEFAULT NULL,
  `text` text NOT NULL,
  `input_tokens` int DEFAULT NULL,
  `output_tokens` int DEFAULT NULL,
  `cache_read_tokens` int DEFAULT NULL,
  `cache_create_tokens` int DEFAULT NULL,
  `created_at` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_conv_created` (`conv_id`,`created_at`)
);
CREATE TABLE IF NOT EXISTS `claude_sessions` (
  `device_id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `updated_at` bigint NOT NULL,
  PRIMARY KEY (`device_id`)
);
CREATE TABLE IF NOT EXISTS `claude_invocation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `trigger_msg_id` bigint DEFAULT NULL COMMENT '触发消息ID，Cron 任务触发时为 NULL',
  `trigger_device_id` varchar(64) DEFAULT NULL,
  `reply_msg_id` bigint DEFAULT NULL,
  `total_rounds` int DEFAULT '0',
  `status` varchar(32) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `finished_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
);
CREATE TABLE IF NOT EXISTS `claude_invocation_round` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `invocation_id` bigint NOT NULL,
  `round_index` int NOT NULL,
  `request_messages` longtext,
  `response_content` longtext,
  `stop_reason` varchar(32) DEFAULT NULL,
  `input_tokens` int DEFAULT '0',
  `output_tokens` int DEFAULT '0',
  `cache_read_input_tokens` int DEFAULT '0',
  `cache_creation_input_tokens` int DEFAULT '0',
  `tool_name` varchar(64) DEFAULT NULL,
  `tool_input` longtext,
  `tool_result` longtext,
  `duration_ms` int DEFAULT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`)
);
CREATE TABLE IF NOT EXISTS `chat_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `file_uuid` varchar(36) NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `mime_type` varchar(100) NOT NULL,
  `file_path` varchar(512) DEFAULT NULL,
  `data` longblob,
  `device_id` varchar(100) NOT NULL,
  `dbctime` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `dbutime` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `file_uuid` (`file_uuid`)
);
CREATE TABLE IF NOT EXISTS `device_info` (
  `device_id` varchar(64) NOT NULL,
  `alias` varchar(64) DEFAULT NULL,
  `manufacturer` varchar(64) DEFAULT NULL,
  `version_code` int DEFAULT '0',
  `version_name` varchar(64) DEFAULT NULL,
  `last_reported_at` bigint DEFAULT NULL,
  `last_heartbeat_at` bigint DEFAULT NULL,
  PRIMARY KEY (`device_id`)
);
CREATE TABLE IF NOT EXISTS `device_heartbeat_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `device_id` varchar(64) NOT NULL,
  `heartbeat_at` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_device_time` (`device_id`,`heartbeat_at`)
);

-- 会话级 Claude CLI 工作目录。null 时 fallback application.yml 的 claude.work-dir
ALTER TABLE `claude_conversations` ADD COLUMN IF NOT EXISTS `work_dir` VARCHAR(512) DEFAULT NULL;
