CREATE TABLE personal_recognition_result (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    file_id CHAR(36) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    provider VARCHAR(24) NOT NULL,
    model VARCHAR(160) NOT NULL,
    task_type VARCHAR(30) NOT NULL,
    result_text LONGTEXT NOT NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_personal_recognition_owner FOREIGN KEY(owner_id) REFERENCES app_user(id),
    CONSTRAINT fk_personal_recognition_file FOREIGN KEY(file_id) REFERENCES file_asset(id)
);
CREATE INDEX idx_personal_recognition_owner_created ON personal_recognition_result(owner_id, created_at);
