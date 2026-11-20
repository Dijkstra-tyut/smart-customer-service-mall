-- 仅在已应用旧版迁移、缺少这些列的数据库执行；新安装初始迁移已包含。
ALTER TABLE cs_agent_run ADD COLUMN actions_json TEXT NULL;
ALTER TABLE cs_live_session ADD COLUMN agent_session_id BIGINT NULL;
CREATE INDEX idx_live_agent_session ON cs_live_session(agent_session_id, id);
ALTER TABLE cs_live_message ADD COLUMN agent_message_id BIGINT NULL UNIQUE;
