ALTER TABLE app_user ADD COLUMN identity_code VARCHAR(36) NULL;

UPDATE app_user
SET identity_code = CONCAT('PKB-', UPPER(REPLACE(UUID(), '-', ''))), updated_at = updated_at
WHERE identity_code IS NULL;

-- The default also covers trusted SQL imports; normal registrations generate a UUID in the application.
ALTER TABLE app_user
    MODIFY COLUMN identity_code VARCHAR(36) NOT NULL DEFAULT (CONCAT('PKB-', UPPER(REPLACE(UUID(), '-', '')))),
    ADD CONSTRAINT uk_app_user_identity_code UNIQUE (identity_code);
