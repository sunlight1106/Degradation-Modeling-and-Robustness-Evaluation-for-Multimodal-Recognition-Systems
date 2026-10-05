-- Read-only MySQL 8.4 diagnostics. Select the intended application database first.
SELECT DATABASE() AS selected_database, VERSION() AS mysql_version,
       @@character_set_database AS database_charset, @@collation_database AS database_collation;

SELECT installed_rank, version, description, installed_on, execution_time, success
FROM flyway_schema_history ORDER BY installed_rank;

SELECT table_name, engine, table_collation, table_rows AS estimated_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY table_name;

SELECT table_name, constraint_name, referenced_table_name, delete_rule, update_rule
FROM information_schema.referential_constraints
WHERE constraint_schema = DATABASE()
ORDER BY table_name, constraint_name;

SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ', ') AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;

-- Expected: no rows. NULL owner is not protected by V6's composite unique key.
SELECT domain, name, COUNT(*) AS duplicate_count
FROM knowledge_topic
WHERE owner_id IS NULL
GROUP BY domain, name HAVING COUNT(*) > 1;

-- Expected: no rows. Polymorphic reference targets are service-validated.
SELECT r.id AS reference_row_id, r.note_id, r.reference_type, r.reference_id
FROM note_reference r
LEFT JOIN file_asset f ON r.reference_type = 'FILE' AND f.id = r.reference_id
LEFT JOIN inference_task t ON r.reference_type = 'TASK' AND t.id = r.reference_id
LEFT JOIN knowledge_entry e ON r.reference_type = 'ENTRY' AND e.id = r.reference_id
WHERE (r.reference_type = 'FILE' AND f.id IS NULL)
   OR (r.reference_type = 'TASK' AND t.id IS NULL)
   OR (r.reference_type = 'ENTRY' AND e.id IS NULL)
   OR r.reference_type NOT IN ('FILE', 'TASK', 'ENTRY')
ORDER BY r.id;
