CREATE TABLE workspace_notice (
 workspace_id BIGINT PRIMARY KEY,
 announcement TEXT NOT NULL,
 pinned_message_id CHAR(36) NULL,
 revision BIGINT NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_notice_workspace FOREIGN KEY(workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
 CONSTRAINT fk_notice_message FOREIGN KEY(pinned_message_id) REFERENCES internal_message(id) ON DELETE SET NULL
);
CREATE TABLE workspace_preference (
 id VARCHAR(36) PRIMARY KEY,
 workspace_id BIGINT NOT NULL,
 owner_id BIGINT NOT NULL,
 pinned BOOLEAN NOT NULL,
 muted BOOLEAN NOT NULL,
 read_through TIMESTAMP(6) NULL,
 read_message_id VARCHAR(36) NOT NULL,
 CONSTRAINT uq_workspace_preference UNIQUE(workspace_id,owner_id),
 CONSTRAINT fk_preference_workspace FOREIGN KEY(workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
 CONSTRAINT fk_preference_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);
