-- 知识库与笔记模块：跨学科知识主题、知识卡、Markdown 笔记、引用关系与分享令牌。
-- 种子知识卡内容由 KnowledgeSeedInitializer 在启动时幂等写入，不放在本脚本中，
-- 以便长中文正文使用 Java 文本块维护，避免 SQL 转义问题。

CREATE TABLE knowledge_topic (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    domain VARCHAR(40) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    owner_id BIGINT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_knowledge_topic_name (domain, name, owner_id),
    INDEX idx_knowledge_topic_domain (domain, sort_order),
    CONSTRAINT fk_knowledge_topic_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE knowledge_entry (
    id CHAR(36) PRIMARY KEY,
    topic_id BIGINT NOT NULL,
    title VARCHAR(180) NOT NULL,
    summary VARCHAR(500),
    body TEXT NOT NULL,
    tags VARCHAR(500),
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    owner_id BIGINT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_knowledge_entry_topic (topic_id, sort_order),
    INDEX idx_knowledge_entry_owner (owner_id, updated_at),
    CONSTRAINT fk_knowledge_entry_topic FOREIGN KEY (topic_id) REFERENCES knowledge_topic(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_entry_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE note (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    title VARCHAR(180) NOT NULL,
    body LONGTEXT NOT NULL,
    tags VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_note_owner_updated (owner_id, updated_at),
    INDEX idx_note_status (status),
    CONSTRAINT fk_note_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);

-- 多态引用：reference_type 为 FILE / TASK / ENTRY，reference_id 为对应 CHAR(36) 主键。
-- 因跨表多态无法建立外键，改由服务层校验目标存在性。
CREATE TABLE note_reference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    note_id CHAR(36) NOT NULL,
    reference_type VARCHAR(20) NOT NULL,
    reference_id VARCHAR(36) NOT NULL,
    label VARCHAR(180),
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_note_reference (note_id, reference_type, reference_id),
    INDEX idx_note_reference_target (reference_type, reference_id),
    CONSTRAINT fk_note_reference_note FOREIGN KEY (note_id) REFERENCES note(id) ON DELETE CASCADE
);

CREATE TABLE note_share (
    id CHAR(36) PRIMARY KEY,
    note_id CHAR(36) NOT NULL,
    shared_by BIGINT NOT NULL,
    token CHAR(32) NOT NULL,
    label VARCHAR(120),
    expires_at TIMESTAMP(6) NULL,
    view_count INT NOT NULL DEFAULT 0,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_note_share_token (token),
    INDEX idx_note_share_note (note_id, created_at),
    CONSTRAINT fk_note_share_note FOREIGN KEY (note_id) REFERENCES note(id) ON DELETE CASCADE,
    CONSTRAINT fk_note_share_creator FOREIGN KEY (shared_by) REFERENCES app_user(id)
);
