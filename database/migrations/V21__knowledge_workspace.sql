ALTER TABLE note ADD COLUMN deleted_at TIMESTAMP(6) NULL;
CREATE INDEX idx_note_owner_deleted ON note(owner_id, deleted_at);

CREATE TABLE note_version (
 id VARCHAR(36) PRIMARY KEY, note_id CHAR(36) NOT NULL, owner_id BIGINT NOT NULL,
 revision BIGINT NOT NULL, title VARCHAR(180) NOT NULL, body LONGTEXT NOT NULL,
 tags VARCHAR(500), library VARCHAR(40) NOT NULL, content_format VARCHAR(12) NOT NULL,
 status VARCHAR(20) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_note_version(note_id, revision),
 INDEX idx_version_owner(owner_id, note_id, created_at),
 CONSTRAINT fk_version_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE learning_record (
 id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, kind VARCHAR(24) NOT NULL,
 title VARCHAR(180) NOT NULL, payload LONGTEXT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 INDEX idx_learning_owner_kind(owner_id, kind, updated_at),
 CONSTRAINT fk_learning_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);
