-- Forward-only access-path improvements for existing repository queries.
-- V1-V6 are intentionally unchanged: deployed Flyway checksums remain valid.
-- No domain rows, permissions or credentials are modified by this migration.

-- Global/admin recent-file and recent-task lists; owner-scoped equivalents
-- already exist in V1. Including id permits deterministic pagination ties.
CREATE INDEX idx_file_created ON file_asset (created_at, id);
CREATE INDEX idx_task_created ON inference_task (created_at, id);
CREATE INDEX idx_user_created ON app_user (created_at, id);
CREATE INDEX idx_user_role_status ON app_user (role_id, status);

-- Active model selection orders by name, rather than task_type.
CREATE INDEX idx_model_status_name ON model_definition (status, name, id);

-- Membership lists filter by user or workspace before sorting chronologically.
CREATE INDEX idx_member_user_created ON workspace_member (user_id, created_at, id);
CREATE INDEX idx_member_workspace_created ON workspace_member (workspace_id, created_at, id);

-- Existing V6 indexes do not fully cover these filter + order combinations.
CREATE INDEX idx_topic_owner_sort ON knowledge_topic (owner_id, sort_order, id);
CREATE INDEX idx_entry_topic_sort_created ON knowledge_entry (topic_id, sort_order, created_at, id);
CREATE INDEX idx_note_owner_status_updated ON note (owner_id, status, updated_at, id);
CREATE INDEX idx_note_reference_note_sort ON note_reference (note_id, sort_order, id);

-- Credential loading is bounded to active keys, in creation order.
CREATE INDEX idx_credential_active_created ON provider_credential (active, created_at, id);
CREATE INDEX idx_credential_provider_active_created ON provider_credential (provider, active, created_at, id);
