CREATE TABLE account_security (
 owner_id BIGINT PRIMARY KEY,
 verified_email VARCHAR(160) NULL,
 mfa_secret TEXT NULL,
 pending_secret TEXT NULL,
 pending_until TIMESTAMP(6) NULL,
 last_counter BIGINT NOT NULL DEFAULT -1,
 recovery_hashes TEXT NOT NULL,
 failed_attempts INT NOT NULL DEFAULT 0,
 locked_until TIMESTAMP(6) NULL,
 CONSTRAINT fk_security_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE TABLE account_challenge (
 id VARCHAR(36) PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 purpose VARCHAR(12) NOT NULL,
 email VARCHAR(160) NOT NULL,
 token_hash CHAR(64) NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 consumed BOOLEAN NOT NULL DEFAULT FALSE,
 delivery VARCHAR(12) NOT NULL,
 INDEX ix_challenge_owner(owner_id, purpose, created_at),
 CONSTRAINT fk_challenge_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);
