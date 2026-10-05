ALTER TABLE app_user ADD COLUMN discoverable BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE note ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE contact_link (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    low_user_id BIGINT NOT NULL,
    high_user_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    low_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    high_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_contact_pair UNIQUE (low_user_id, high_user_id),
    CONSTRAINT ck_contact_pair CHECK (low_user_id < high_user_id),
    CONSTRAINT fk_contact_low FOREIGN KEY (low_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_contact_high FOREIGN KEY (high_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_contact_requester FOREIGN KEY (requester_id) REFERENCES app_user(id),
    INDEX idx_contact_high (high_user_id, status)
);
CREATE TABLE chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    contact_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    client_id VARCHAR(36) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_chat_retry UNIQUE (sender_id, client_id),
    CONSTRAINT fk_chat_contact FOREIGN KEY (contact_id) REFERENCES contact_link(id),
    CONSTRAINT fk_chat_sender FOREIGN KEY (sender_id) REFERENCES app_user(id),
    INDEX idx_chat_contact (contact_id, id)
);
