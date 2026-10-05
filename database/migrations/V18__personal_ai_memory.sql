CREATE TABLE personal_ai_memory (
 id VARCHAR(36) PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL,
 body VARCHAR(1000) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 revision BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_ai_memory_owner FOREIGN KEY(owner_id) REFERENCES app_user(id)
);
CREATE INDEX idx_ai_memory_owner ON personal_ai_memory(owner_id,created_at,id);
