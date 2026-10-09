CREATE TABLE workspace_shortcut (
 id VARCHAR(36) PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 kind VARCHAR(12) NOT NULL,
 title VARCHAR(180) NOT NULL,
 resource_key VARCHAR(64) NOT NULL,
 source_kind VARCHAR(16) NULL,
 source_id VARCHAR(36) NULL,
 filters_json LONGTEXT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_shortcut_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
 CONSTRAINT uq_shortcut_resource UNIQUE(owner_id,kind,resource_key),
 INDEX idx_shortcut_owner_time(owner_id,kind,created_at,id)
);
