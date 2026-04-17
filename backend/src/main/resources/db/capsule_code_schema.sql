/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `claude_conversations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `claude_conversations` (
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
) ENGINE=InnoDB AUTO_INCREMENT=603 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `claude_messages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `claude_messages` (
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
) ENGINE=InnoDB AUTO_INCREMENT=46842 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `claude_sessions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `claude_sessions` (
  `device_id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `updated_at` bigint NOT NULL,
  PRIMARY KEY (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `claude_invocation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `claude_invocation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `trigger_msg_id` bigint DEFAULT NULL COMMENT '触发消息ID，Cron 任务触发时为 NULL',
  `trigger_device_id` varchar(64) DEFAULT NULL,
  `reply_msg_id` bigint DEFAULT NULL,
  `total_rounds` int DEFAULT '0',
  `status` varchar(32) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `finished_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=77 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `claude_invocation_round`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `claude_invocation_round` (
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
) ENGINE=InnoDB AUTO_INCREMENT=89 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `claude_usage_snapshot`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `claude_usage_snapshot` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增id',
  `account` varchar(16) NOT NULL COMMENT '账号标签：pro | max',
  `five_hour_util` decimal(6,2) DEFAULT NULL COMMENT '当前会话利用率（%）',
  `seven_day_util` decimal(6,2) DEFAULT NULL COMMENT '当前周利用率（%）',
  `resets_at_five_hour` varchar(64) DEFAULT NULL COMMENT '5小时窗口重置时间（ISO8601）',
  `resets_at_seven_day` varchar(64) DEFAULT NULL COMMENT '7天窗口重置时间（ISO8601）',
  `dbctime` datetime(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `dbutime` datetime(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `captured_at` datetime(3) NOT NULL COMMENT '数据采集时间',
  `seven_day_sonnet_util` decimal(5,2) DEFAULT NULL COMMENT 'Sonnet only 7天用量百分比（仅 Max）',
  `resets_at_seven_day_sonnet` varchar(50) DEFAULT NULL COMMENT 'Sonnet only 7天重置时间',
  PRIMARY KEY (`id`),
  KEY `idx_account_captured` (`account`,`captured_at`)
) ENGINE=InnoDB AUTO_INCREMENT=26518 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Claude.ai 用量快照历史';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `chat_file`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_file` (
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
) ENGINE=InnoDB AUTO_INCREMENT=759 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

DROP TABLE IF EXISTS `device_info`;
CREATE TABLE `device_info` (
  `device_id` varchar(64) NOT NULL,
  `alias` varchar(64) DEFAULT NULL,
  `manufacturer` varchar(64) DEFAULT NULL,
  `version_code` int DEFAULT '0',
  `version_name` varchar(64) DEFAULT NULL,
  `last_reported_at` bigint DEFAULT NULL,
  `last_heartbeat_at` bigint DEFAULT NULL,
  PRIMARY KEY (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
DROP TABLE IF EXISTS `device_heartbeat_log`;
CREATE TABLE `device_heartbeat_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `device_id` varchar(64) NOT NULL,
  `heartbeat_at` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_device_time` (`device_id`,`heartbeat_at`)
) ENGINE=InnoDB AUTO_INCREMENT=9326 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


