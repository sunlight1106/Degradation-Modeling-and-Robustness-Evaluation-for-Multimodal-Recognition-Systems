-- User-owned BYOK settings; the administrator key ring remains separate.
-- No plaintext secrets or note/context/result content is persisted here.
CREATE TABLE personal_ai_setting (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    provider VARCHAR(24) NOT NULL,
    model VARCHAR(160) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    encrypted_key VARCHAR(6000) NOT NULL,
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_personal_ai_owner_provider UNIQUE (owner_id, provider),
    CONSTRAINT fk_personal_ai_setting_owner FOREIGN KEY (owner_id) REFERENCES app_user(id)
);
CREATE TABLE personal_ai_usage (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    provider VARCHAR(24) NOT NULL,
    model VARCHAR(160) NOT NULL,
    action VARCHAR(20) NOT NULL,
    status VARCHAR(16) NOT NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    error_code VARCHAR(60),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_personal_ai_usage_owner FOREIGN KEY (owner_id) REFERENCES app_user(id)
);
CREATE INDEX idx_personal_ai_usage_owner_time ON personal_ai_usage(owner_id, created_at);
