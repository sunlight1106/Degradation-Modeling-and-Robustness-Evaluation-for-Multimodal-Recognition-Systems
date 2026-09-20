ALTER TABLE model_definition
    ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'DEEPSEEK' AFTER version;

ALTER TABLE file_asset
    ADD COLUMN scan_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' AFTER source,
    ADD COLUMN scan_engine VARCHAR(80) NULL AFTER scan_status,
    ADD COLUMN scanned_at TIMESTAMP(6) NULL AFTER scan_engine;

ALTER TABLE inference_task
    ADD COLUMN provider VARCHAR(20) NULL AFTER model_id,
    ADD COLUMN input_tokens BIGINT NULL AFTER optimized_latency_ms,
    ADD COLUMN output_tokens BIGINT NULL AFTER input_tokens,
    ADD COLUMN cost_cny DECIMAL(14,6) NULL AFTER output_tokens;

UPDATE inference_task task
JOIN model_definition model ON model.id = task.model_id
SET task.provider = model.provider
WHERE task.provider IS NULL;

CREATE TABLE user_wallet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    balance_cny DECIMAL(14,4) NOT NULL,
    monthly_quota_cny DECIMAL(14,4) NOT NULL,
    month_spent_cny DECIMAL(14,4) NOT NULL DEFAULT 0,
    quota_period_start DATE NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_wallet_user (user_id),
    CONSTRAINT fk_wallet_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE wallet_ledger (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    amount DECIMAL(14,4) NOT NULL,
    balance_after DECIMAL(14,4) NOT NULL,
    reference_id VARCHAR(64),
    description VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_ledger_user_created (user_id, created_at),
    CONSTRAINT fk_ledger_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE recharge_order (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    method VARCHAR(30) NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    bank_last4 CHAR(4),
    verification_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    paid_at TIMESTAMP(6),
    INDEX idx_recharge_user_created (user_id, created_at),
    CONSTRAINT fk_recharge_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE provider_budget (
    provider VARCHAR(20) PRIMARY KEY,
    monthly_budget_cny DECIMAL(14,4) NOT NULL,
    used_cny DECIMAL(14,4) NOT NULL DEFAULT 0,
    period_start DATE NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO provider_budget(provider, monthly_budget_cny, used_cny, period_start)
VALUES
    ('DEEPSEEK', 500.0000, 0, DATE_FORMAT(UTC_DATE(), '%Y-%m-01')),
    ('KIMI', 500.0000, 0, DATE_FORMAT(UTC_DATE(), '%Y-%m-01')),
    ('QWEN', 500.0000, 0, DATE_FORMAT(UTC_DATE(), '%Y-%m-01'));
