CREATE TABLE IF NOT EXISTS agent_checkpoint (
    token VARCHAR(255) PRIMARY KEY,
    state_json CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decision VARCHAR(255),
    comment VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS agent_shared_memory (
    id VARCHAR(255) PRIMARY KEY,
    scope VARCHAR(255),
    scope_key VARCHAR(255),
    agent_id VARCHAR(255),
    role VARCHAR(255),
    content VARCHAR(2000),
    token_count INT,
    created_at TIMESTAMP WITH TIME ZONE
);
