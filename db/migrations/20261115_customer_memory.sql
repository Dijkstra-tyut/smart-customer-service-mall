-- 在 20261110_customer_agent.sql 之后执行。增量迁移，不清空既有对话。
-- 原始消息继续保存在 cs_message；这里只保存可追溯的明确偏好及会话摘要。
CREATE TABLE IF NOT EXISTS cs_user_memory (
    user_id INT NOT NULL,
    attribute VARCHAR(40) NOT NULL,
    preference_value VARCHAR(200) NOT NULL,
    source_session_id BIGINT NOT NULL,
    source_message_id BIGINT NOT NULL,
    source_quote VARCHAR(500) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, attribute),
    INDEX idx_user_memory_source (source_session_id, source_message_id)
);
CREATE TABLE IF NOT EXISTS cs_conversation_summary (
    session_id BIGINT PRIMARY KEY,
    user_id INT NOT NULL,
    summary TEXT NOT NULL,
    through_message_id BIGINT NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_summary_user (user_id, updated_at)
);
-- 与历史消息游标分页对应；MySQL 支持使用此索引倒序扫描。
CREATE INDEX idx_message_session_id ON cs_message(session_id, id);
