-- Additive migration for the customer agent. Apply once before enabling the new service.
-- Existing customer messages and sessions are preserved.
CREATE TABLE cs_agent_run (
    id VARCHAR(64) PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id INT NOT NULL,
    input_message_id BIGINT NULL,
    active_session_id BIGINT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    token_expires_at TIMESTAMP NOT NULL,
    human_task_id VARCHAR(80),
    reason VARCHAR(40),
    last_error VARCHAR(500),
    actions_json TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agent_run_session ON cs_agent_run(session_id, created_at);
CREATE INDEX idx_agent_run_input ON cs_agent_run(session_id, input_message_id);

CREATE TABLE cs_human_task (
    id VARCHAR(80) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    run_revision INT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    reason VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    draft LONGTEXT,
    review_json LONGTEXT,
    evidence_json LONGTEXT,
    action_json LONGTEXT,
    action_stale BOOLEAN NOT NULL DEFAULT FALSE,
    decision_id VARCHAR(80) UNIQUE,
    decision_json LONGTEXT,
    decided_by INT,
    attempts INT NOT NULL DEFAULT 0,
    lease_until TIMESTAMP NULL,
    next_attempt_at TIMESTAMP NULL,
    last_error VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_human_queue ON cs_human_task(status, next_attempt_at, created_at);
CREATE INDEX idx_human_run ON cs_human_task(run_id);

CREATE TABLE cs_ticket (
    id VARCHAR(64) PRIMARY KEY,
    user_id INT NOT NULL,
    session_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    summary VARCHAR(200) NOT NULL,
    details LONGTEXT NOT NULL,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_cs_ticket_user ON cs_ticket(user_id, created_at);

CREATE TABLE cs_agent_operation (
    id VARCHAR(100) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    result_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE cs_agent_message (
    message_key VARCHAR(160) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    message_id BIGINT NOT NULL UNIQUE,
    author_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
