-- MySQL 8.4 synthetic benchmark fixture, never application data.
-- Run with mysql --batch --database=rv_perf_<suffix> < this-file (do NOT use --force).
-- Requires completed Flyway/bootstrap, no tasks or files, and an existing admin + LPR model.
-- Does not create storage objects, invoke models, spend tokens, or alter accounts.
-- Both guard and every persistent INSERT are gated: even --force cannot write outside rv_perf_*.
SET @perf_owner = (SELECT id FROM app_user WHERE username = 'admin' AND status = 'ACTIVE' LIMIT 1);
SET @perf_model = (SELECT id FROM model_definition WHERE task_type = 'LICENSE_PLATE' AND status = 'ACTIVE' ORDER BY id LIMIT 1);
SET @perf_provider = (SELECT provider FROM model_definition WHERE id = @perf_model);
SET @perf_allowed = COALESCE(
    DATABASE() REGEXP '^rv_perf_[A-Za-z0-9_]+$'
    AND (SELECT COUNT(*) FROM inference_task) = 0
    AND (SELECT COUNT(*) FROM file_asset) = 0
    AND @perf_owner IS NOT NULL AND @perf_model IS NOT NULL, FALSE);
CREATE TEMPORARY TABLE perf_fixture_guard (allowed TINYINT NOT NULL CHECK (allowed = 1));
INSERT INTO perf_fixture_guard VALUES (@perf_allowed);

CREATE TEMPORARY TABLE perf_sequence (n INT NOT NULL PRIMARY KEY);
INSERT INTO perf_sequence(n)
SELECT ones.n + tens.n * 10 + hundreds.n * 100
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) hundreds;

START TRANSACTION;
INSERT INTO file_asset(id, original_name, stored_name, content_type, size_bytes, sha256, storage_path,
                       owner_id, source, scan_status, scan_engine, scanned_at, created_at)
SELECT CONCAT('10000000-0000-4000-8000-', LPAD(n, 12, '0')),
       CONCAT('synthetic-', n, '.png'), CONCAT('synthetic-', n, '.png'), 'image/png', 1,
       SHA2(CONCAT('synthetic-fixture-', n), 256), CONCAT('performance-fixture/no-object-', n),
       @perf_owner, 'UPLOAD', 'SKIPPED', 'SYNTHETIC_BENCHMARK_NO_BYTES', NULL,
       TIMESTAMPADD(SECOND, n, '2025-01-01 00:00:00')
FROM perf_sequence WHERE @perf_allowed = 1;

INSERT INTO inference_task(id, trace_id, task_type, status, enhancement_enabled, input_file_id,
                           model_id, provider, requested_by, baseline_confidence, optimized_confidence,
                           baseline_result, optimized_result, created_at, completed_at)
SELECT CONCAT('20000000-0000-4000-8000-', LPAD(n, 12, '0')),
       CONCAT('30000000-0000-4000-8000-', LPAD(n, 12, '0')), 'LICENSE_PLATE',
       CASE MOD(n, 3) WHEN 0 THEN 'COMPLETED' WHEN 1 THEN 'FAILED' ELSE 'PENDING' END,
       TRUE, CONCAT('10000000-0000-4000-8000-', LPAD(n, 12, '0')),
       @perf_model, @perf_provider, @perf_owner,
       CASE WHEN MOD(n, 3) = 2 THEN NULL ELSE 0.5 END,
       CASE WHEN MOD(n, 3) = 0 THEN 0.9 ELSE 0.4 END,
       JSON_OBJECT('fixture', REPEAT('x', 4096)), JSON_OBJECT('fixture', REPEAT('x', 4096)),
       TIMESTAMPADD(SECOND, n, '2025-01-01 00:00:00'),
       CASE WHEN MOD(n, 3) = 2 THEN NULL ELSE TIMESTAMPADD(SECOND, n + 1, '2025-01-01 00:00:00') END
FROM perf_sequence WHERE @perf_allowed = 1;
COMMIT;
SELECT DATABASE() AS benchmark_database, @perf_allowed AS fixture_allowed,
       (SELECT COUNT(*) FROM inference_task) AS tasks,
       (SELECT COUNT(*) FROM file_asset) AS files;
DROP TEMPORARY TABLE perf_sequence;
DROP TEMPORARY TABLE perf_fixture_guard;
