package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.InferenceTaskRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real transactions, row locks, FileService and billing; synthetic storage/model/AV only. */
@SpringBootTest(properties = {"app.bootstrap.enabled=false", "app.worker.enabled=false", "app.rate-limit.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=12", "spring.datasource.hikari.connection-timeout=15000"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InferenceRecoveryIntegrationTest {
    @Autowired InferenceService inference;
    @Autowired InferenceTaskRepository tasks;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @SpyBean BillingService billing;
    @MockBean ModelInvocationService invocation;
    @MockBean InferenceQueueService queue;
    @MockBean ObjectStorageService storage;
    @MockBean AntivirusService antivirus;
    @MockBean ContentInspectionService inspection;
    @MockBean MediaProcessingService media;
    private TransactionTemplate tx;
    private static String schema, adminUrl, databaseUsername, databasePassword;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String configured = System.getenv("MYSQL_TEST_URL");
        if (configured == null || configured.isBlank()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:inference_recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000;DATABASE_TO_LOWER=TRUE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.flyway.enabled", () -> false);
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
            return;
        }
        int pathStart = configured.indexOf('/', "jdbc:mysql://".length());
        if (!configured.startsWith("jdbc:mysql://") || pathStart < 0) throw new IllegalArgumentException("Invalid MYSQL_TEST_URL");
        int queryStart = configured.indexOf('?', pathStart);
        String options = queryStart < 0 ? "" : configured.substring(queryStart);
        String server = configured.substring(0, pathStart + 1);
        adminUrl = server + options;
        databaseUsername = System.getenv().getOrDefault("MYSQL_TEST_USERNAME", "root");
        databasePassword = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        String created = "rv_schema_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(adminUrl, databaseUsername, databasePassword);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + created + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        schema = created;
        registry.add("spring.datasource.url", () -> server + schema + options);
        registry.add("spring.datasource.username", () -> databaseUsername);
        registry.add("spring.datasource.password", () -> databasePassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @AfterAll static void removeOnlyOurDatabase() throws Exception {
        if (schema != null) {
            try (var connection = DriverManager.getConnection(adminUrl, databaseUsername, databasePassword);
                 var statement = connection.createStatement()) { statement.execute("DROP DATABASE `" + schema + "`"); }
        }
    }

    @BeforeEach void prepare() {
        tx = new TransactionTemplate(transactions);
        when(invocation.invoke(any(), any(), any(), anyBoolean(), anyString())).thenReturn(demo());
        when(storage.exists(anyString())).thenReturn(true);
        when(storage.get(anyString())).thenReturn(new byte[]{1, 2, 3}); // Never read real user files.
        when(storage.backendName()).thenReturn("synthetic-test");
    }

    @Test void enhancedFileFailurePersistsFailedAfterRollbackAndWorkerContinues() {
        Fixture bad = seed(true, "RESEARCHER"), good = seed(false, "RESEARCHER");
        when(queue.poll()).thenReturn(bad.id(), good.id(), null);
        assertThatCode(() -> new InferenceQueueWorker(queue, inference, 32).poll()).doesNotThrowAnyException();
        InferenceTaskEntity stored = tasks.findById(bad.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(InferenceStatus.FAILED);
        assertThat(stored.getErrorMessage()).isEqualTo("生成对比文件失败");
        assertThat(stored.getCompletedAt()).isNotNull();
        assertThat(stored.getBaselineResult()).isNull();
        assertThat(stored.getOutputFile()).isNull();
        assertThat(ledgerCount(bad.id())).isZero();
        assertThat(tasks.findById(good.id()).orElseThrow().getStatus()).isEqualTo(InferenceStatus.COMPLETED);
        assertThat(ledgerCount(good.id())).isEqualTo(1);
        inference.processQueuedTask(bad.id());
        verify(invocation, times(2)).invoke(any(), any(), any(), anyBoolean(), anyString());
    }

    @Test void sixConcurrentDeliveriesInvokeAndBillOnce() throws Exception {
        Fixture task = seed(false, "RESEARCHER");
        AtomicInteger calls = new AtomicInteger();
        when(invocation.invoke(any(), any(), any(), anyBoolean(), eq(task.trace()))).thenAnswer(call -> {
            calls.incrementAndGet(); Thread.sleep(100); return demo();
        });
        concurrently(task.id());
        assertThat(calls.get()).isEqualTo(1);
        verify(billing, times(1)).recordUsage(any(), eq(ModelProvider.DEMO), eq(0L), eq(0L), eq(task.id()));
        assertThat(ledgerCount(task.id())).isEqualTo(1);
        assertThat(tasks.findById(task.id()).orElseThrow().getStatus()).isEqualTo(InferenceStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT balance_cny FROM user_wallet WHERE user_id=?", java.math.BigDecimal.class, task.owner().getId()))
                .isEqualByComparingTo("20.00");
        assertThat(tasks.findById(task.id()).orElseThrow().getCostCny()).isEqualByComparingTo("0");
    }

    @Test void failedBillingTransactionRollsBackItsLedgerBeforeFailureIsStored() {
        Fixture task = seed(false, "RESEARCHER");
        doAnswer(call -> {
            call.callRealMethod(); em.flush();
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "TEST_FAILURE", "合成计费写入故障");
        }).when(billing).recordUsage(any(), any(), anyLong(), anyLong(), eq(task.id()));
        inference.processQueuedTask(task.id());
        InferenceTaskEntity stored = tasks.findById(task.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(InferenceStatus.FAILED);
        assertThat(stored.getErrorMessage()).isEqualTo("合成计费写入故障");
        assertThat(stored.getCompletedAt()).isNotNull();
        assertThat(stored.getBaselineResult()).isNull();
        assertThat(stored.getInputTokens()).isNull();
        assertThat(ledgerCount(task.id())).isZero();
        inference.processQueuedTask(task.id());
        verify(billing, times(1)).recordUsage(any(), any(), anyLong(), anyLong(), eq(task.id()));
    }

    @Test void sixConcurrentFailuresRemainFailedWithoutBilling() throws Exception {
        Fixture task = seed(true, "RESEARCHER");
        concurrently(task.id());
        InferenceTaskEntity stored = tasks.findById(task.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(InferenceStatus.FAILED);
        assertThat(stored.getErrorMessage()).isEqualTo("生成对比文件失败");
        assertThat(stored.getCompletedAt()).isNotNull();
        assertThat(ledgerCount(task.id())).isZero();
        verify(billing, never()).recordUsage(any(), any(), anyLong(), anyLong(), anyString());
        // Failed synthetic attempts may repeat between rollback and the conditional FAILED transaction.
        verify(invocation, atLeastOnce()).invoke(any(), any(), any(), anyBoolean(), eq(task.trace()));
    }

    @Test void competingSuccessBetweenRollbackAndFailureMarkerCannotBeOverwritten() throws Exception {
        Fixture task = seed(false, "RESEARCHER");
        when(invocation.invoke(any(), any(), any(), anyBoolean(), eq(task.trace())))
                .thenThrow(new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "TEST_FAILURE", "合成故障")).thenReturn(demo());
        assertThatThrownBy(() -> inference.executePendingTask(task.id())).isInstanceOf(BusinessException.class);
        assertThat(tasks.findById(task.id()).orElseThrow().getStatus()).isEqualTo(InferenceStatus.PENDING);
        // Precisely exercise the legal interleaving before the first coordinator marks failure.
        inference.processQueuedTask(task.id());
        InferenceTaskEntity completed = tasks.findById(task.id()).orElseThrow();
        inference.failPendingTask(task.id(), "stale failure");
        InferenceTaskEntity stored = tasks.findById(task.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(InferenceStatus.COMPLETED);
        assertThat(stored.getErrorMessage()).isNull();
        assertThat(stored.getBaselineResult()).isEqualTo(completed.getBaselineResult());
        assertThat(stored.getCompletedAt()).isEqualTo(completed.getCompletedAt());
        assertThat(ledgerCount(task.id())).isEqualTo(1);
    }

    @Test void terminalAndMissingDeliveriesDoNothing() {
        for (InferenceStatus status : List.of(InferenceStatus.COMPLETED, InferenceStatus.FAILED, InferenceStatus.RUNNING)) {
            Fixture task = seed(false, "RESEARCHER");
            tx.executeWithoutResult(s -> tasks.findById(task.id()).orElseThrow().setStatus(status));
            inference.processQueuedTask(task.id()); inference.failPendingTask(task.id(), "stale");
            assertThat(tasks.findById(task.id()).orElseThrow().getStatus()).isEqualTo(status);
        }
        inference.processQueuedTask(UUID.randomUUID().toString());
        inference.failPendingTask(UUID.randomUUID().toString(), "missing");
        verifyNoInteractions(invocation);
        verify(billing, never()).recordUsage(any(), any(), anyLong(), anyLong(), anyString());
    }

    @Test void ownerCanRecoverLostPopAndRepeatedRecoveryKeepsSameTaskAndTrace() throws Exception {
        Fixture task = seed(false, "RESEARCHER");
        when(queue.poll()).thenReturn(task.id(), null);
        assertThat(queue.poll()).isEqualTo(task.id()); // Simulated crash after RPOP, before execution.
        assertThat(queue.poll()).isNull();
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/v1/inference/tasks/{id}/recover", task.id())
                        .with(user(task.owner().getUsername()).authorities(() -> "experiment:run", () -> "experiment:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(task.id()))
                .andExpect(jsonPath("$.data.traceId").value(task.trace()))
                .andExpect(jsonPath("$.data.requestedById").value(task.owner().getId()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        verify(queue, times(2)).enqueue(task.id());
        concurrently(task.id());
        assertThat(ledgerCount(task.id())).isEqualTo(1);
        mvc.perform(post("/api/v1/inference/tasks/{id}/recover", task.id())
                        .with(user(task.owner().getUsername()).authorities(() -> "experiment:run")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TASK_NOT_PENDING"));
    }

    @Test void recoveryRequiresOwnerOwnInputAndRunPermissionIncludingAdministrators() throws Exception {
        Fixture task = seed(false, "RESEARCHER"), other = seed(false, "RESEARCHER"), admin = seed(false, "ADMIN");
        mvc.perform(post("/api/v1/inference/tasks/{id}/recover", task.id())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/inference/tasks/{id}/recover", task.id())
                        .with(user(task.owner().getUsername()).authorities(() -> "experiment:read"))).andExpect(status().isForbidden());
        for (Fixture stranger : List.of(other, admin)) mvc.perform(post("/api/v1/inference/tasks/{id}/recover", task.id())
                        .with(user(stranger.owner().getUsername()).authorities(() -> "experiment:run", () -> "experiment:read:any")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TASK_RECOVERY_DENIED"));
        // Administrator's own task referencing someone else's file still cannot be recovered.
        jdbc.update("UPDATE inference_task SET input_file_id=(SELECT input_file_id FROM (SELECT * FROM inference_task) tmp WHERE id=?) WHERE id=?", task.id(), admin.id());
        mvc.perform(post("/api/v1/inference/tasks/{id}/recover", admin.id())
                        .with(user(admin.owner().getUsername()).authorities(() -> "experiment:run")))
                .andExpect(status().isForbidden());
        verify(queue, never()).enqueue(anyString());
    }

    @Test void ownerAdministratorCanRecoverButTerminalAndMissingTasksAreRejected() throws Exception {
        Fixture task = seed(false, "ADMIN");
        mvc.perform(post("/api/v1/inference/tasks/{id}/recover", task.id())
                        .with(user(task.owner().getUsername()).authorities(() -> "experiment:run"))).andExpect(status().isOk());
        for (InferenceStatus status : List.of(InferenceStatus.COMPLETED, InferenceStatus.FAILED, InferenceStatus.RUNNING)) {
            tx.executeWithoutResult(s -> tasks.findById(task.id()).orElseThrow().setStatus(status));
            mvc.perform(post("/api/v1/inference/tasks/{id}/recover", task.id())
                            .with(user(task.owner().getUsername()).authorities(() -> "experiment:run")))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TASK_NOT_PENDING"));
        }
        mvc.perform(post("/api/v1/inference/tasks/{id}/recover", UUID.randomUUID().toString())
                        .with(user(task.owner().getUsername()).authorities(() -> "experiment:run"))).andExpect(status().isNotFound());
        verify(queue, times(1)).enqueue(task.id());
    }

    @Test void recoveryQueueFailurePreservesPendingAndSyntheticGuardRejectsBothEntryPoints() throws Exception {
        Fixture task = seed(false, "RESEARCHER");
        doThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_QUEUE_UNAVAILABLE", "队列暂时不可用"))
                .when(queue).enqueue(task.id());
        mvc.perform(post("/api/v1/inference/tasks/{id}/recover", task.id())
                        .with(user(task.owner().getUsername()).authorities(() -> "experiment:run")))
                .andExpect(status().isServiceUnavailable());
        assertThat(tasks.findById(task.id()).orElseThrow().getStatus()).isEqualTo(InferenceStatus.PENDING);
        doThrow(new BusinessException(HttpStatus.CONFLICT, "PERSONAL_AI_REQUIRED", "仅限 DEMO"))
                .when(invocation).assertLegacyAvailable();
        mvc.perform(post("/api/v1/inference/tasks/{id}/recover", task.id())
                        .with(user(task.owner().getUsername()).authorities(() -> "experiment:run")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PERSONAL_AI_REQUIRED"));
        inference.processQueuedTask(task.id());
        assertThat(tasks.findById(task.id()).orElseThrow().getStatus()).isEqualTo(InferenceStatus.FAILED);
        verify(invocation, never()).invoke(any(), any(), any(), anyBoolean(), anyString());
        verify(billing, never()).recordUsage(any(), any(), anyLong(), anyLong(), anyString());
        verify(queue, times(1)).enqueue(task.id());
    }

    @Test void initialEnqueueTimeoutPreservesPendingOrAlreadyCompletedWork() throws Exception {
        for (boolean workerCompleted : List.of(false, true)) {
            Fixture fixture = seed(false, "RESEARCHER");
            String[] created = new String[1];
            String body = tx.execute(status -> {
                InferenceTaskEntity task = tasks.findById(fixture.id()).orElseThrow();
                return "{\"fileId\":\"" + task.getInputFile().getId() + "\",\"modelId\":" + task.getModel().getId()
                        + ",\"taskType\":\"RECEIPT\",\"enhancementEnabled\":false}";
            });
            doAnswer(call -> {
                created[0] = call.getArgument(0);
                if (workerCompleted) inference.processQueuedTask(created[0]);
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_QUEUE_UNAVAILABLE", "Synthetic timeout after LPUSH");
            }).when(queue).enqueue(anyString());
            mvc.perform(post("/api/v1/inference/tasks").contentType("application/json").content(body)
                            .with(user(fixture.owner().getUsername()).authorities(() -> "experiment:run")))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("INFERENCE_QUEUE_UNAVAILABLE"));
            InferenceTaskEntity stored = tasks.findById(created[0]).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(workerCompleted ? InferenceStatus.COMPLETED : InferenceStatus.PENDING);
            assertThat(stored.getErrorMessage()).isNull();
            assertThat(ledgerCount(created[0])).isEqualTo(workerCompleted ? 1 : 0);
            doNothing().when(queue).enqueue(anyString());
            if (!workerCompleted) mvc.perform(post("/api/v1/inference/tasks/{id}/recover", created[0])
                            .with(user(fixture.owner().getUsername()).authorities(() -> "experiment:run")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));
        }
    }

    private Fixture seed(boolean enhanced, String roleCode) {
        return tx.execute(status -> {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            RoleEntity role = em.createQuery("from RoleEntity where code=:code", RoleEntity.class).setParameter("code", roleCode)
                    .getResultStream().findFirst().orElseGet(() -> {
                        RoleEntity created = new RoleEntity(roleCode, "Fixture", null, Set.of("experiment:run", "experiment:read")); em.persist(created); return created;
                    });
            UserEntity owner = new UserEntity("q-" + suffix, "fixture", "Owner", suffix + "@example.invalid", role); em.persist(owner);
            FileAssetEntity file = new FileAssetEntity("image.png", "fixture", "image/png", 3, "a".repeat(64),
                    "synthetic-key-" + suffix, owner, FileSource.UPLOAD, FileScanStatus.CLEAN, "fixture"); em.persist(file);
            ModelDefinitionEntity model = new ModelDefinitionEntity("q-" + suffix, "Fixture", "1", ModelProvider.DEMO, TaskType.RECEIPT, "synthetic"); em.persist(model);
            InferenceTaskEntity task = new InferenceTaskEntity(UUID.randomUUID().toString(), TaskType.RECEIPT, enhanced, file, model, owner); em.persist(task);
            return new Fixture(task.getId(), task.getTraceId(), owner);
        });
    }
    private long ledgerCount(String taskId) { return jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE reference_id=? AND type='USAGE'", Long.class, taskId); }
    private ModelInvocationService.InvocationResult demo() { return new ModelInvocationService.InvocationResult(.8, 1, Map.of("adapter", "DEMO"), ModelProvider.DEMO, 0, 0); }
    private record Fixture(String id, String trace, UserEntity owner) {}
    private void concurrently(String id) throws Exception {
        var pool = Executors.newFixedThreadPool(6); CountDownLatch ready = new CountDownLatch(6), start = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, 6).mapToObj(i -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
                inference.processQueuedTask(id); return null;
            })).toList();
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); start.countDown();
            for (var future : futures) future.get(45, TimeUnit.SECONDS);
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }
}
