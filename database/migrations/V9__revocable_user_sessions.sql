-- Sessions store an opaque ID, never the bearer token. Existing sessionless JWTs
-- deliberately require login again after this migration is deployed.
CREATE TABLE user_session (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    last_seen_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    user_agent VARCHAR(400) NULL,
    CONSTRAINT fk_user_session_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE INDEX idx_user_session_owner_expiry ON user_session(user_id, expires_at);
CREATE INDEX idx_user_session_expiry ON user_session(expires_at);
