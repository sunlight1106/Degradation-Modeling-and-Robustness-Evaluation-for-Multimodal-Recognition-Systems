ALTER TABLE vocabulary_question ADD COLUMN study_style VARCHAR(12) NOT NULL DEFAULT 'CHOICE';
UPDATE vocabulary_question SET study_style='RECALL' WHERE practice_kind<>'CHOICE';
ALTER TABLE vocabulary_question ADD COLUMN skill_detail VARCHAR(500) NULL;
CREATE TABLE vocabulary_skill (
 id VARCHAR(36) PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 term_key VARCHAR(100) NOT NULL,
 skill_key CHAR(64) NOT NULL,
 kind VARCHAR(16) NOT NULL,
 detail VARCHAR(500) NOT NULL,
 correct_count INT NOT NULL,
 wrong_count INT NOT NULL,
 independent_count INT NOT NULL,
 last_attempt TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_vocab_skill UNIQUE(owner_id,term_key,skill_key),
 INDEX ix_vocab_skill_owner(owner_id,wrong_count),
 CONSTRAINT fk_vocab_skill_owner FOREIGN KEY(owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);
