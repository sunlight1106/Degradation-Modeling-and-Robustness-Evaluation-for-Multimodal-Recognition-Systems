ALTER TABLE note ADD COLUMN parent_id CHAR(36) NULL, ADD INDEX idx_note_parent (parent_id), ADD CONSTRAINT fk_note_parent FOREIGN KEY (parent_id) REFERENCES note(id) ON DELETE SET NULL;
