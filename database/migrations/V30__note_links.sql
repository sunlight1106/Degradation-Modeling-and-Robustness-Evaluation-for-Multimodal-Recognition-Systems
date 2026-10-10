CREATE TABLE note_link (
 id VARCHAR(36) PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 source_id CHAR(36) NOT NULL,
 target_id CHAR(36) NOT NULL,
 CONSTRAINT uq_note_link UNIQUE(source_id,target_id),
 INDEX ix_note_link_target(owner_id,target_id),
 CONSTRAINT fk_note_link_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
 CONSTRAINT fk_note_link_source FOREIGN KEY(source_id) REFERENCES note(id) ON DELETE CASCADE,
 CONSTRAINT fk_note_link_target FOREIGN KEY(target_id) REFERENCES note(id) ON DELETE CASCADE
);
