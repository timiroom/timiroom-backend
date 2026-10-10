CREATE TABLE slack_account_link (
    team_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    member_id BIGINT NOT NULL,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(team_id,user_id),
    UNIQUE(team_id,member_id)
);
CREATE TABLE slack_link_code (
    code_hash VARCHAR(64) PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX slack_link_code_actor_idx ON slack_link_code(team_id,user_id,created_at);
CREATE TABLE slack_project_channel (
    project_id BIGINT PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL,
    channel_id VARCHAR(64) NOT NULL,
    configured_by BIGINT NOT NULL,
    configured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(team_id,channel_id)
);
CREATE TABLE slack_command_request (
    request_id VARCHAR(64) PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    channel_id VARCHAR(64) NOT NULL,
    command_text VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claimed_at TIMESTAMP WITH TIME ZONE,
    state VARCHAR(32) NOT NULL,
    error_code VARCHAR(64)
);
CREATE INDEX slack_command_queue_idx ON slack_command_request(state,created_at);
CREATE TABLE slack_notification (
    event_key VARCHAR(128) PRIMARY KEY,
    project_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    channel_id VARCHAR(64) NOT NULL,
    message_text VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(32) NOT NULL,
    claimed_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64)
);
CREATE INDEX slack_notification_queue_idx ON slack_notification(state,created_at);
