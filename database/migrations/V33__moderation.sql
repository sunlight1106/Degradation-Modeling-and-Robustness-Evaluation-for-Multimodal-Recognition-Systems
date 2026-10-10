CREATE TABLE moderation_report (
 id VARCHAR(36) PRIMARY KEY, source_type VARCHAR(12) NOT NULL, source_id VARCHAR(36) NOT NULL,
 reporter_id BIGINT NOT NULL, target_id BIGINT NOT NULL, target_name VARCHAR(80) NOT NULL,
 target_identity VARCHAR(36) NOT NULL, evidence TEXT NOT NULL, reason VARCHAR(500) NOT NULL,
 status VARCHAR(12) NOT NULL, urgent BOOLEAN NOT NULL DEFAULT FALSE,
 review_reason VARCHAR(1000), reviewer_id BIGINT, created_at TIMESTAMP(6) NOT NULL, reviewed_at TIMESTAMP(6),
 CONSTRAINT uq_moderation_report UNIQUE(source_type,source_id,reporter_id),
 CONSTRAINT fk_moderation_reporter FOREIGN KEY(reporter_id) REFERENCES app_user(id),
 CONSTRAINT fk_moderation_target FOREIGN KEY(target_id) REFERENCES app_user(id),
 CONSTRAINT fk_moderation_reviewer FOREIGN KEY(reviewer_id) REFERENCES app_user(id),
 INDEX ix_moderation_queue(status,urgent,created_at), INDEX ix_moderation_reporter(reporter_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE moderation_penalty (
 id VARCHAR(36) PRIMARY KEY, report_id VARCHAR(36) NOT NULL, target_id BIGINT NOT NULL, actor_id BIGINT,
 kind VARCHAR(12) NOT NULL, features VARCHAR(200) NOT NULL, reason VARCHAR(1000) NOT NULL,
 automatic BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 revoked_at TIMESTAMP(6), appeal VARCHAR(1000), appeal_at TIMESTAMP(6), appeal_reply VARCHAR(1000),
 CONSTRAINT fk_moderation_penalty_report FOREIGN KEY(report_id) REFERENCES moderation_report(id),
 CONSTRAINT fk_moderation_penalty_target FOREIGN KEY(target_id) REFERENCES app_user(id),
 CONSTRAINT fk_moderation_penalty_actor FOREIGN KEY(actor_id) REFERENCES app_user(id),
 INDEX ix_moderation_active(target_id,revoked_at,expires_at), INDEX ix_moderation_penalty_report(report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE moderation_event (
 id VARCHAR(36) PRIMARY KEY, report_id VARCHAR(36) NOT NULL, actor_id BIGINT,
 action VARCHAR(30) NOT NULL, detail VARCHAR(2000) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_moderation_event_report FOREIGN KEY(report_id) REFERENCES moderation_report(id),
 CONSTRAINT fk_moderation_event_actor FOREIGN KEY(actor_id) REFERENCES app_user(id),
 INDEX ix_moderation_event_report(report_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO moderation_report(id,source_type,source_id,reporter_id,target_id,target_name,target_identity,evidence,reason,status,urgent,review_reason,created_at)
 SELECT r.id,'MESSAGE',r.message_id,r.reporter_id,m.sender_id,u.display_name,u.identity_code,
 CONCAT(m.subject,CHAR(10),m.body),r.reason,
 CASE WHEN r.status='OPEN' THEN 'PENDING' ELSE r.status END,FALSE,
 CASE WHEN r.status='OPEN' THEN NULL ELSE '历史群组举报已处理，未记录平台处罚' END,r.created_at
 FROM group_report r JOIN internal_message m ON m.id=r.message_id JOIN app_user u ON u.id=m.sender_id;
