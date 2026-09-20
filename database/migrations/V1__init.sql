CREATE TABLE app_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE role_permission (
    role_id BIGINT NOT NULL,
    permission_code VARCHAR(80) NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES app_role(id) ON DELETE CASCADE
);

CREATE TABLE app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(60) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    email VARCHAR(160) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES app_role(id)
);

CREATE TABLE file_asset (
    id CHAR(36) PRIMARY KEY,
    original_name VARCHAR(255) NOT NULL,
    stored_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    storage_path VARCHAR(600) NOT NULL,
    owner_id BIGINT NOT NULL,
    source VARCHAR(30) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_file_owner_created (owner_id, created_at),
    INDEX idx_file_sha256 (sha256),
    CONSTRAINT fk_file_owner FOREIGN KEY (owner_id) REFERENCES app_user(id)
);

CREATE TABLE model_definition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    version VARCHAR(80) NOT NULL,
    task_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    endpoint VARCHAR(500),
    description VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_model_code_version (code, version),
    INDEX idx_model_task_status (task_type, status)
);

CREATE TABLE inference_task (
    id CHAR(36) PRIMARY KEY,
    trace_id CHAR(36) NOT NULL UNIQUE,
    task_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    enhancement_enabled BOOLEAN NOT NULL,
    input_file_id CHAR(36) NOT NULL,
    output_file_id CHAR(36),
    model_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    baseline_confidence DOUBLE,
    optimized_confidence DOUBLE,
    baseline_latency_ms BIGINT,
    optimized_latency_ms BIGINT,
    baseline_result LONGTEXT,
    optimized_result LONGTEXT,
    error_message VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    INDEX idx_task_user_created (requested_by, created_at),
    INDEX idx_task_status_created (status, created_at),
    CONSTRAINT fk_task_input_file FOREIGN KEY (input_file_id) REFERENCES file_asset(id),
    CONSTRAINT fk_task_output_file FOREIGN KEY (output_file_id) REFERENCES file_asset(id),
    CONSTRAINT fk_task_model FOREIGN KEY (model_id) REFERENCES model_definition(id),
    CONSTRAINT fk_task_user FOREIGN KEY (requested_by) REFERENCES app_user(id)
);
