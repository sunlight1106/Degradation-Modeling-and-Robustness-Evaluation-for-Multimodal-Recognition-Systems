ALTER TABLE app_user ADD COLUMN access_expires_at TIMESTAMP(6) NULL;
CREATE TABLE user_permission_override (
 user_id BIGINT NOT NULL, permission_code VARCHAR(80) NOT NULL, allowed BOOLEAN NOT NULL,
 PRIMARY KEY(user_id, permission_code),
 CONSTRAINT fk_user_override FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE TABLE admin_audit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 operator_id BIGINT NOT NULL, operator_name VARCHAR(60) NOT NULL,
 action VARCHAR(40) NOT NULL, target_type VARCHAR(20) NOT NULL, target_id BIGINT NOT NULL,
 detail VARCHAR(12000) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 INDEX idx_admin_audit_time(created_at, id),
 INDEX idx_admin_audit_target(target_type, target_id, created_at)
);
-- app_user(created_at, id) already has idx_user_created from V7.
CREATE INDEX idx_session_created_user ON user_session(created_at, user_id);
CREATE INDEX idx_session_last_seen ON user_session(last_seen_at);
CREATE INDEX idx_note_created ON note(created_at);
CREATE INDEX idx_personal_ai_usage_created ON personal_ai_usage(created_at);

-- Preserve previously available authenticated modules during upgrade. Subsequent
-- role edits and individual overrides can disable any of these explicitly.
INSERT INTO role_permission(role_id, permission_code)
SELECT r.id, p.code FROM app_role r CROSS JOIN (
 SELECT 'contacts:use' AS code UNION ALL SELECT 'group:use' UNION ALL SELECT 'vocabulary:use'
 UNION ALL SELECT 'research:use' UNION ALL SELECT 'personal-ai:manage'
 UNION ALL SELECT 'personal-ai:use' UNION ALL SELECT 'training:use' UNION ALL SELECT 'message:read'
) p WHERE NOT EXISTS (SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_code = p.code);
