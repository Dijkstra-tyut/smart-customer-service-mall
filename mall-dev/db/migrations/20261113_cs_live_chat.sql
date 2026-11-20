-- 人工实时客服（WebSocket）追加迁移：只新增表，不改动 cs_session / cs_message 及其 Agent 表。
-- 与 AI 客服会话隔离：AI 会话走 cs_session，人工实时会话走 cs_live_session。

CREATE TABLE cs_live_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    agent_session_id BIGINT NULL,
    admin_id INT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'waiting',
    source VARCHAR(16) NOT NULL DEFAULT 'user',
    close_reason VARCHAR(64) NULL,
    last_message_preview VARCHAR(200) NULL,
    last_message_at DATETIME NULL,
    unread_user INT NOT NULL DEFAULT 0,
    unread_admin INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_live_session_user ON cs_live_session(user_id, status);
CREATE INDEX idx_live_session_queue ON cs_live_session(status, update_time);
CREATE INDEX idx_live_session_admin ON cs_live_session(admin_id, status);
CREATE INDEX idx_live_agent_session ON cs_live_session(agent_session_id, id);

CREATE TABLE cs_live_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    agent_message_id BIGINT NULL UNIQUE,
    sender_type VARCHAR(16) NOT NULL,
    sender_id INT NULL,
    content_type VARCHAR(16) NOT NULL DEFAULT 'text',
    content TEXT NOT NULL,
    client_msg_id VARCHAR(64) NULL,
    is_read TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (session_id, client_msg_id)
);
CREATE INDEX idx_live_message_session ON cs_live_message(session_id, id);

CREATE TABLE cs_live_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_type VARCHAR(16) NULL,
    actor_id INT NULL,
    payload TEXT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX idx_live_event_session ON cs_live_event(session_id, id);
