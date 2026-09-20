ALTER TABLE recharge_order
    MODIFY verification_hash VARCHAR(64) NULL,
    ADD COLUMN phone_masked VARCHAR(32) NULL AFTER bank_last4,
    ADD COLUMN verification_attempts INT NOT NULL DEFAULT 0 AFTER verification_hash,
    ADD COLUMN payment_token_hash CHAR(64) NULL AFTER verification_attempts,
    ADD COLUMN qr_payload VARCHAR(1000) NULL AFTER payment_token_hash,
    ADD UNIQUE KEY uk_recharge_payment_token (payment_token_hash);

CREATE TABLE workspace (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,
    color VARCHAR(20) NOT NULL,
    owner_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_workspace_owner FOREIGN KEY (owner_id) REFERENCES app_user(id)
);

CREATE TABLE workspace_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    member_role VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_workspace_member (workspace_id, user_id),
    CONSTRAINT fk_workspace_member_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_member_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE workspace_member_permission (
    workspace_member_id BIGINT NOT NULL,
    permission_code VARCHAR(40) NOT NULL,
    PRIMARY KEY (workspace_member_id, permission_code),
    CONSTRAINT fk_workspace_permission_member FOREIGN KEY (workspace_member_id) REFERENCES workspace_member(id) ON DELETE CASCADE
);

CREATE TABLE internal_message (
    id CHAR(36) PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    subject VARCHAR(180) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_message_sender_created (sender_id, created_at),
    CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES app_user(id)
);

CREATE TABLE message_recipient (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id CHAR(36) NOT NULL,
    recipient_id BIGINT NOT NULL,
    read_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_message_recipient (message_id, recipient_id),
    INDEX idx_recipient_created (recipient_id, message_id),
    CONSTRAINT fk_recipient_message FOREIGN KEY (message_id) REFERENCES internal_message(id) ON DELETE CASCADE,
    CONSTRAINT fk_recipient_user FOREIGN KEY (recipient_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE message_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id CHAR(36) NOT NULL,
    file_id CHAR(36) NOT NULL,
    UNIQUE KEY uk_message_attachment_file (message_id, file_id),
    CONSTRAINT fk_attachment_message FOREIGN KEY (message_id) REFERENCES internal_message(id) ON DELETE CASCADE,
    CONSTRAINT fk_attachment_file FOREIGN KEY (file_id) REFERENCES file_asset(id)
);

CREATE TABLE provider_credential (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider VARCHAR(20) NOT NULL,
    label VARCHAR(80) NOT NULL,
    encrypted_secret TEXT NOT NULL,
    fingerprint VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    disabled_at TIMESTAMP(6) NULL,
    INDEX idx_provider_credential_active (provider, active),
    CONSTRAINT fk_credential_creator FOREIGN KEY (created_by) REFERENCES app_user(id)
);
