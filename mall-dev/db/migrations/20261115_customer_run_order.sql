-- 仅用于已经应用旧版 20261110_customer_agent.sql 的数据库。
-- 新安装的初始迁移已包含该字段，不应重复执行本文件。
ALTER TABLE cs_agent_run ADD COLUMN input_message_id BIGINT NULL;
CREATE INDEX idx_agent_run_input ON cs_agent_run(session_id, input_message_id);
