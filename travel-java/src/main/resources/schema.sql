-- ============================================================
-- 「AI 旅行管家」建表脚本（权威 DDL）
-- 应用库：ai_travel（手动执行一次）
-- 测试库：ai_travel_test（测试启动时 spring.sql.init 自动执行）
-- 全部使用 CREATE TABLE IF NOT EXISTS，可重复执行
-- ============================================================

-- 用户（极简单表：用户名密码 + 可选邮箱 + 可选微信 openid + 推送偏好）
CREATE TABLE IF NOT EXISTS `user` (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL COMMENT '用户名，唯一',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，不存明文',
    email         VARCHAR(100) NULL COMMENT '提醒邮件收件地址',
    wechat_openid VARCHAR(64)  NULL COMMENT '微信测试号 openid，绑定后写入',
    notify_mail   TINYINT      NOT NULL DEFAULT 1 COMMENT '推送偏好：邮件渠道开关（契约 §2.5b）',
    notify_wechat TINYINT      NOT NULL DEFAULT 1 COMMENT '推送偏好：微信渠道开关（契约 §2.5b）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email),
    UNIQUE KEY uk_openid (wechat_openid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户';

-- 会话（一次多轮对话的容器）
CREATE TABLE IF NOT EXISTS conversation (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    title      VARCHAR(100) NOT NULL DEFAULT '新对话',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_conversation_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会话';

-- 消息（role: USER / ASSISTANT / TOOL）
-- 注意：历史回放只取 USER 与最终 ASSISTANT（tool_calls_json 为空），
CREATE TABLE IF NOT EXISTS message (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT       NOT NULL,
    role            VARCHAR(10)  NOT NULL,
    content         MEDIUMTEXT   NULL,
    tool_calls_json MEDIUMTEXT   NULL COMMENT 'ASSISTANT 工具调用轨迹，仅落库展示不参与回放',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_message_conversation (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '消息';

-- 行程（推荐结果落库；start_date 是日程日期与行前提醒的锚点）
CREATE TABLE IF NOT EXISTS trip (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT        NOT NULL,
    city           VARCHAR(50)   NOT NULL,
    days           INT           NOT NULL,
    budget         DECIMAL(10,2) NOT NULL,
    start_date     DATE          NOT NULL COMMENT '出发日期',
    status         VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/FINISHED',
    recommend_json MEDIUMTEXT    NULL COMMENT '模型原始推荐 JSON',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_trip_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '行程';

-- 日程项（提醒扫描的领域对象）
-- remind_at 为派生字段 = start_time - remind_minutes_before
CREATE TABLE IF NOT EXISTS schedule_item (
    id                    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT       NOT NULL,
    trip_id               BIGINT       NULL COMMENT '来源行程，手工创建为 NULL',
    title                 VARCHAR(100) NOT NULL,
    type                  VARCHAR(20)  NOT NULL DEFAULT 'TODO' COMMENT 'SPOT/FOOD/TRANSPORT/TODO/DEPART',
    start_time            DATETIME     NOT NULL,
    end_time              DATETIME     NULL,
    location              VARCHAR(200) NULL,
    note                  VARCHAR(500) NULL,
    remind_minutes_before INT          NOT NULL DEFAULT 30,
    remind_at             DATETIME     NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DISPATCHED/SENT/FAILED/CANCELLED/DONE',
    source                VARCHAR(10)  NOT NULL DEFAULT 'MANUAL' COMMENT 'AI/MANUAL/TRIP',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'DISPATCHED 卡死兜底扫描的时间判据',
    KEY idx_schedule_status_remind (status, remind_at),
    KEY idx_schedule_user_start (user_id, start_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '日程项';

-- 提醒发送记录（排查与补偿依据）
CREATE TABLE IF NOT EXISTS reminder_log (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    schedule_item_id BIGINT       NOT NULL,
    channel          VARCHAR(10)  NOT NULL COMMENT 'SSE/MAIL/WECHAT',
    status           VARCHAR(10)  NOT NULL COMMENT 'SUCCESS/FAIL',
    detail           VARCHAR(500) NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_reminder_log_item (schedule_item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '提醒发送记录';
