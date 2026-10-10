CREATE TABLE personal_preference (
 owner_id BIGINT PRIMARY KEY, payload TEXT NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_personal_preference_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE TABLE note_reminder (
 note_id CHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, due_date DATE NOT NULL, repeat_days INT NOT NULL DEFAULT 0,
 CONSTRAINT fk_reminder_note FOREIGN KEY(note_id) REFERENCES note(id) ON DELETE CASCADE,
 CONSTRAINT fk_reminder_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
 INDEX ix_reminder_owner_due(owner_id,due_date)
);
CREATE TABLE account_activity (
 id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NULL, action VARCHAR(30) NOT NULL,
 outcome VARCHAR(40) NOT NULL, network VARCHAR(80) NOT NULL, device VARCHAR(180) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_activity_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE SET NULL,
 INDEX ix_activity_owner_time(owner_id,created_at)
);
CREATE TABLE account_closure (
 owner_id BIGINT PRIMARY KEY, mode VARCHAR(20) NOT NULL, discoverable BOOLEAN NOT NULL, closed_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_closure_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE TABLE account_cleanup (
 id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, kind VARCHAR(20) NOT NULL,
 resource VARCHAR(600) NOT NULL, attempts INT NOT NULL DEFAULT 0, retry_at TIMESTAMP(6) NOT NULL,
 INDEX ix_cleanup_retry(retry_at)
);
ALTER TABLE account_challenge ADD COLUMN context_hash VARCHAR(64) NULL;
