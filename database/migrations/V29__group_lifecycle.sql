ALTER TABLE workspace ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workspace ADD COLUMN dissolved BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workspace ADD COLUMN accept_requests BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE internal_message ADD COLUMN recalled_at TIMESTAMP(6) NULL;
CREATE TABLE group_invitation (
 id VARCHAR(36) PRIMARY KEY,
 workspace_id BIGINT NOT NULL,
 target_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 kind VARCHAR(12) NOT NULL,
 status VARCHAR(12) NOT NULL,
 role VARCHAR(12) NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_group_invite UNIQUE(workspace_id,target_id),
 CONSTRAINT fk_invite_workspace FOREIGN KEY(workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
 CONSTRAINT fk_invite_target FOREIGN KEY(target_id) REFERENCES app_user(id) ON DELETE CASCADE,
 CONSTRAINT fk_invite_actor FOREIGN KEY(actor_id) REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE TABLE group_report (
 id VARCHAR(36) PRIMARY KEY,
 workspace_id BIGINT NOT NULL,
 message_id CHAR(36) NOT NULL,
 reporter_id BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 status VARCHAR(12) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_group_report UNIQUE(message_id,reporter_id),
 CONSTRAINT fk_report_workspace FOREIGN KEY(workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
 CONSTRAINT fk_report_message FOREIGN KEY(message_id) REFERENCES internal_message(id) ON DELETE CASCADE,
 CONSTRAINT fk_report_owner FOREIGN KEY(reporter_id) REFERENCES app_user(id) ON DELETE CASCADE
);
