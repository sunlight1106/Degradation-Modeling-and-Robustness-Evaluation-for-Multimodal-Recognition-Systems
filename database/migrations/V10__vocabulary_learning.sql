-- Personal vocabulary training. All write operations lock the owning app_user row.
CREATE TABLE vocabulary_book (
 id VARCHAR(36) PRIMARY KEY, owner_id BIGINT, title VARCHAR(100) NOT NULL,
 description VARCHAR(600) NOT NULL, attribution VARCHAR(300) NOT NULL,
 level VARCHAR(40) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_vocab_book_owner FOREIGN KEY(owner_id) REFERENCES app_user(id)
);
CREATE INDEX idx_vocab_book_owner ON vocabulary_book(owner_id,created_at);
CREATE TABLE vocabulary_word (
 id VARCHAR(36) PRIMARY KEY, book_id VARCHAR(36) NOT NULL, term VARCHAR(80) NOT NULL,
 ipa VARCHAR(120) NOT NULL, pos VARCHAR(24) NOT NULL, meaning VARCHAR(160) NOT NULL,
 example_text VARCHAR(400) NOT NULL, example_translation VARCHAR(400) NOT NULL,
 distractors VARCHAR(1500) NOT NULL, sort_order INT NOT NULL,
 CONSTRAINT fk_vocab_word_book FOREIGN KEY(book_id) REFERENCES vocabulary_book(id),
 CONSTRAINT uq_vocab_book_term UNIQUE(book_id,term)
);
CREATE TABLE vocabulary_profile (
 owner_id BIGINT PRIMARY KEY, zone_id VARCHAR(80), daily_goal INT NOT NULL DEFAULT 10,
 selected_book_id VARCHAR(36), updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_vocab_profile_owner FOREIGN KEY(owner_id) REFERENCES app_user(id)
);
CREATE TABLE vocabulary_progress (
 id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, word_id VARCHAR(36) NOT NULL,
 learning_correct INT NOT NULL DEFAULT 0, review_stage INT NOT NULL DEFAULT 0,
 wrong_count INT NOT NULL DEFAULT 0, mistake BOOLEAN NOT NULL DEFAULT FALSE,
 starred BOOLEAN NOT NULL DEFAULT FALSE, due_date DATE, learned_date DATE,
 last_attempt_at TIMESTAMP(6), last_review_date DATE,
 CONSTRAINT fk_vocab_progress_owner FOREIGN KEY(owner_id) REFERENCES app_user(id),
 CONSTRAINT fk_vocab_progress_word FOREIGN KEY(word_id) REFERENCES vocabulary_word(id),
 CONSTRAINT uq_vocabulary_owner_word UNIQUE(owner_id,word_id),
 CONSTRAINT chk_vocab_learning CHECK(learning_correct BETWEEN 0 AND 4)
);
CREATE INDEX idx_vocab_progress_due ON vocabulary_progress(owner_id,due_date);
CREATE TABLE vocabulary_question (
 id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, word_id VARCHAR(36) NOT NULL,
 book_id VARCHAR(36) NOT NULL, mode VARCHAR(12) NOT NULL, options_json VARCHAR(2400) NOT NULL,
 correct_option_id VARCHAR(36) NOT NULL, created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 answered_at TIMESTAMP(6), study_date DATE, answer_correct BOOLEAN, result_json VARCHAR(5000),
 CONSTRAINT fk_vocab_question_owner FOREIGN KEY(owner_id) REFERENCES app_user(id),
 CONSTRAINT fk_vocab_question_word FOREIGN KEY(word_id) REFERENCES vocabulary_word(id),
 CONSTRAINT fk_vocab_question_book FOREIGN KEY(book_id) REFERENCES vocabulary_book(id)
);
CREATE INDEX idx_vocab_question_owner_time ON vocabulary_question(owner_id,created_at);
CREATE INDEX idx_vocab_question_owner_date ON vocabulary_question(owner_id,study_date);
