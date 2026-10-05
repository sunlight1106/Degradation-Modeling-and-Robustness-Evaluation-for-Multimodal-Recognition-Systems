ALTER TABLE internal_message ADD COLUMN workspace_id BIGINT NULL;
ALTER TABLE internal_message ADD COLUMN reply_to_id CHAR(36) NULL;
ALTER TABLE internal_message ADD CONSTRAINT fk_message_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id);
ALTER TABLE internal_message ADD CONSTRAINT fk_message_reply FOREIGN KEY (reply_to_id) REFERENCES internal_message(id);
CREATE INDEX idx_message_workspace_created ON internal_message(workspace_id, created_at, id);
