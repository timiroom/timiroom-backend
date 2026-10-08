CREATE TABLE integration_grant (
    grant_id UUID PRIMARY KEY,
    member_id BIGINT NOT NULL,
    client_id VARCHAR(128) NOT NULL,
    authorization_id VARCHAR(100) NOT NULL UNIQUE,
    project_ids TEXT NOT NULL,
    scopes TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX integration_grant_member_idx ON integration_grant(member_id,created_at);
