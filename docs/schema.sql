-- ============================================================
-- maibao_technology_application  建表脚本
-- MySQL 8 / utf8mb4 / InnoDB
-- 执行：mysql -u root -p maibao_technology_application < docs/schema.sql
-- 说明：与 docs/03-数据库设计.md 第 2 节一致；数据库结构以本脚本为准，
--       应用侧 spring.jpa.hibernate.ddl-auto 为 validate
-- ============================================================

SET NAMES utf8mb4;

-- ---------- 2.1 对话明细流水 ----------
CREATE TABLE IF NOT EXISTS `user_query_log` (
  `id`                bigint        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `request_id`        varchar(64)   NOT NULL                COMMENT '请求追踪号（UUID），同时作为幂等锚点',
  `user_id`           varchar(64)   NOT NULL                COMMENT '用户标识，空值归入 anonymous',
  `model`             varchar(64)   NOT NULL                COMMENT '模型名，可带厂商前缀，如 mock:demo',
  `source`            varchar(16)   NOT NULL                COMMENT '调用入口：SYNC / STREAM',
  `prompt`            varchar(2000) DEFAULT NULL            COMMENT '问题原文，超长截断',
  `reply`             varchar(4000) DEFAULT NULL            COMMENT '回答原文，超长截断',
  `prompt_tokens`     int           NOT NULL                COMMENT '输入 token 估算值',
  `completion_tokens` int           NOT NULL                COMMENT '输出 token 估算值',
  `total_tokens`      int           NOT NULL                COMMENT '总 token 数',
  `cost_amount`       decimal(12,6) NOT NULL                COMMENT '本次费用（元），失败为 0',
  `latency_ms`        int           NOT NULL                COMMENT '耗时（毫秒）',
  `status`            varchar(16)   NOT NULL                COMMENT 'SUCCESS / FAILED',
  `error_msg`         varchar(255)  DEFAULT NULL            COMMENT '失败原因',
  `created_at`        datetime(6)   NOT NULL                COMMENT '落库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_query_request` (`request_id`),
  KEY `idx_query_user_id` (`user_id`, `id`),
  KEY `idx_query_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话明细流水';

-- ---------- 2.2 成本聚合 ----------
CREATE TABLE IF NOT EXISTS `cost_accounting` (
  `id`                bigint        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `user_id`           varchar(64)   NOT NULL                COMMENT '用户标识',
  `model`             varchar(64)   NOT NULL                COMMENT '模型名',
  `stat_date`         date          NOT NULL                COMMENT '账期日',
  `request_count`     bigint        NOT NULL DEFAULT 0      COMMENT '调用次数',
  `prompt_tokens`     bigint        NOT NULL DEFAULT 0      COMMENT '输入 token 累计',
  `completion_tokens` bigint        NOT NULL DEFAULT 0      COMMENT '输出 token 累计',
  `total_tokens`      bigint        NOT NULL DEFAULT 0      COMMENT '总 token 累计',
  `cost_amount`       decimal(12,6) NOT NULL DEFAULT 0      COMMENT '费用累计（元）',
  `currency`          varchar(8)    NOT NULL DEFAULT 'CNY'  COMMENT '币种',
  `updated_at`        datetime(6)   NOT NULL                COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cost_user_model_date` (`user_id`, `model`, `stat_date`),
  KEY `idx_cost_user_date_model` (`user_id`, `stat_date`, `model`),
  KEY `idx_cost_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='成本按用户x模型x天聚合';

-- ---------- 2.3 发布状态机 ----------
CREATE TABLE IF NOT EXISTS `publish_record` (
  `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `node_id`      varchar(64)  NOT NULL                COMMENT '节点标识',
  `status`       varchar(16)  NOT NULL                COMMENT 'SCHEDULED / PUBLISHING / SUCCESS / FAILED / CANCELLED',
  `is_scheduled` int          NOT NULL DEFAULT 0      COMMENT '0 立即发布，1 定时发布',
  `scheduled_at` datetime(6)  DEFAULT NULL            COMMENT '到期时间',
  `claimed_at`   datetime(6)  DEFAULT NULL            COMMENT '被抢占的时刻，用于识别卡死',
  `gray_percent` int          NOT NULL DEFAULT 100    COMMENT '灰度进度',
  `fail_reason`  varchar(255) DEFAULT NULL            COMMENT '失败原因',
  `created_at`   datetime(6)  NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_scheduled_at` (`status`, `scheduled_at`),
  KEY `idx_status_claimed_at` (`status`, `claimed_at`),
  KEY `idx_publish_node` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发布状态机';

-- ---------- 2.4 MQ 消费去重 ----------
CREATE TABLE IF NOT EXISTS `processed_task` (
  `id`         bigint      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `task_id`    varchar(64) NOT NULL                COMMENT '任务幂等键',
  `created_at` datetime(6) NOT NULL                COMMENT '处理时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_processed_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MQ 消费去重';

-- ---------- 2.5 重试耗尽落表 ----------
CREATE TABLE IF NOT EXISTS `failed_task` (
  `id`         bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `task_id`    varchar(64)  NOT NULL                COMMENT '任务幂等键',
  `payload`    varchar(512) DEFAULT NULL            COMMENT '消息体片段',
  `reason`     varchar(512) DEFAULT NULL            COMMENT '失败原因',
  `attempts`   int          NOT NULL                COMMENT '已重试次数',
  `created_at` datetime(6)  NOT NULL                COMMENT '落表时间',
  PRIMARY KEY (`id`),
  KEY `idx_failed_task_id` (`task_id`),
  KEY `idx_failed_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='重试耗尽的失败任务';

-- ---------- 2.6 本地消息表（outbox） ----------
CREATE TABLE IF NOT EXISTS `mq_outbox` (
  `id`            bigint        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `message_id`    varchar(64)   NOT NULL                COMMENT '业务幂等键（taskId）',
  `exchange_name` varchar(64)   NOT NULL                COMMENT '目标交换机',
  `routing_key`   varchar(64)   NOT NULL                COMMENT '路由键',
  `payload`       varchar(1024) NOT NULL                COMMENT '消息体 JSON',
  `status`        varchar(16)   NOT NULL                COMMENT 'PENDING / SENT / CONSUMED / FAILED',
  `attempts`      int           NOT NULL DEFAULT 0      COMMENT '已投递次数',
  `next_retry_at` datetime(6)   NOT NULL                COMMENT '下次可投递时间',
  `last_error`    varchar(255)  DEFAULT NULL            COMMENT '最后一次失败原因',
  `created_at`    datetime(6)   NOT NULL                COMMENT '创建时间',
  `updated_at`    datetime(6)   NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbox_message` (`message_id`),
  KEY `idx_outbox_status_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本地消息表';

-- ---------- 2.7 密钥 ----------
CREATE TABLE IF NOT EXISTS `api_key` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `owner_id`    varchar(64)  NOT NULL                COMMENT '归属人',
  `project_id`  varchar(64)  NOT NULL                COMMENT '归属项目',
  `provider`    varchar(32)  NOT NULL                COMMENT '厂商',
  `cipher_text` varchar(512) NOT NULL                COMMENT 'AES-GCM 密文',
  `masked`      varchar(64)  NOT NULL                COMMENT '脱敏串，仅用于展示',
  `created_at`  datetime(6)  NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_apikey_owner` (`owner_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='API 密钥（密文入库）';
