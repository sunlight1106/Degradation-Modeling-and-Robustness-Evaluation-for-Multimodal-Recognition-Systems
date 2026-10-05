package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.PersonalAiDtos.*;
import com.robustvision.platform.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Same post-provider commit/rollback contract on H2 or an explicitly supplied disposable MySQL server. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PersonalAiPersistenceService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PersonalAiPersistenceIntegrationTest {
    @PersistenceContext EntityManager entities;
    @Autowired PersonalAiPersistenceService persistence;
    @Autowired PersonalAiUsageRepository usage;
    @Autowired PersonalRecognitionResultRepository results;
    @Autowired FileAssetRepository files;
    @Autowired PlatformTransactionManager transactions;
    private UserEntity owner;
    private FileAssetEntity file;
    private PersonalAiSettingEntity setting;
    private final CurrentUserService current = mock(CurrentUserService.class);
    private final PersonalAiSettingRepository settings = mock(PersonalAiSettingRepository.class);
    private final FileService fileService = mock(FileService.class);
    private final SecretEncryptionService encryption = mock(SecretEncryptionService.class);
    private final PersonalAiTransport transport = mock(PersonalAiTransport.class);
    private final PersonalAiEndpointPolicy endpoints = new PersonalAiEndpointPolicy("");
    private static String schema, adminUrl, databaseUsername, databasePassword;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        String configured = System.getenv("MYSQL_TEST_URL");
        if (configured == null || configured.isBlank()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:personal_ai_persistence;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            return;
        }
        int path = configured.indexOf('/', "jdbc:mysql://".length());
        if (!configured.startsWith("jdbc:mysql://") || path < 0)
            throw new IllegalArgumentException("MYSQL_TEST_URL must name one disposable MySQL server");
        int query = configured.indexOf('?', path);
        String options = query < 0 ? "" : configured.substring(query);
        String server = configured.substring(0, path + 1);
        adminUrl = server + options;
        databaseUsername = System.getenv().getOrDefault("MYSQL_TEST_USERNAME", "root");
        databasePassword = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        String fresh = "rv_schema_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(adminUrl, databaseUsername, databasePassword);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + fresh + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        schema = fresh;
        registry.add("spring.datasource.url", () -> server + schema + options);
        registry.add("spring.datasource.username", () -> databaseUsername);
        registry.add("spring.datasource.password", () -> databasePassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @AfterAll static void removeOnlyOwnedSchema() throws Exception {
        if (schema != null) try (var connection = DriverManager.getConnection(adminUrl, databaseUsername, databasePassword);
                                var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + schema + "`");
        }
    }

    @BeforeEach void fixture() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            var role = new RoleEntity("AI_" + suffix.substring(0, 12), "Synthetic role", null, Set.of());
            entities.persist(role);
            owner = new UserEntity("ai_" + suffix.substring(0, 12), "synthetic", "Synthetic", suffix + "@example.invalid", role);
            entities.persist(owner);
            file = new FileAssetEntity("synthetic.png", "fixture", "image/png", 3, "a".repeat(64), "fixture", owner,
                    FileSource.UPLOAD, FileScanStatus.CLEAN, "synthetic");
            entities.persist(file);
        });
        setting = new PersonalAiSettingEntity(owner.getId(), AiProvider.OPENAI);
        setting.update("synthetic-model", "https://api.openai.com/v1", "synthetic-ciphertext", true);
        when(current.requireCurrent()).thenReturn(owner);
        when(settings.findByOwnerIdAndProvider(owner.getId(), AiProvider.OPENAI)).thenReturn(Optional.of(setting));
        when(encryption.decrypt("synthetic-ciphertext")).thenReturn("synthetic-test-only-key");
        when(fileService.readBytes(any())).thenReturn(new byte[]{1, 2, 3});
        var payload = new PersonalAiTransport.Payload("https://api.openai.com/v1/chat/completions", "{\"synthetic\":true}");
        when(transport.prepareVision(any(), any(), any(), any(), any(), any(), any())).thenReturn(payload);
        when(transport.prepare(any(), any(), any(), any(), any())).thenReturn(payload);
        when(transport.execute(any(), any(), eq("synthetic-test-only-key"))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("No database transaction across provider I/O").isFalse();
            return new PersonalAiTransport.Completion("Synthetic completed output", 21, 8);
        });
    }

    private PersonalRecognitionService recognition(PersonalAiPersistenceService writes) {
        return new PersonalRecognitionService(current, files, fileService, settings, results, writes, encryption,
                endpoints, transport, new PersonalAiRateLimiter(), true);
    }
    private PersonalRecognitionService.Result execute(PersonalAiPersistenceService writes) {
        var service = recognition(writes);
        var preview = service.preview(AiProvider.OPENAI, file.getId(), TaskType.RECEIPT);
        var completed = service.execute(preview.previewToken(), true);
        assertThatThrownBy(() -> service.execute(preview.previewToken(), true)).isInstanceOf(BusinessException.class);
        verify(transport).execute(any(), any(), any());
        return completed;
    }
    private void assertNoWrites() {
        assertThat(results.findTop100ByOwnerIdOrderByCreatedAtDesc(owner.getId())).isEmpty();
        assertThat(usage.findTop100ByOwnerIdOrderByCreatedAtDesc(owner.getId())).isEmpty();
    }

    @Test void completionCommitsRecognitionAndReportedUsageTogetherAfterProviderReturns() {
        var completed = execute(persistence);
        assertThat(completed.persistenceStatus()).isEqualTo("SAVED");
        assertThat(completed.warning()).isNull();
        assertThat(results.findById(completed.id())).isPresent();
        assertThat(usage.findTop100ByOwnerIdOrderByCreatedAtDesc(owner.getId())).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(row.getInputTokens()).isEqualTo(21L);
            assertThat(row.getOutputTokens()).isEqualTo(8L);
        });
    }

    @Test void usageWriteFailureRollsBackAnAlreadyFlushedRecognitionAndPreservesOutput() {
        var failingUsage = mock(PersonalAiUsageRepository.class);
        when(failingUsage.save(any())).thenAnswer(call -> {
            results.flush();
            assertThat(results.findTop100ByOwnerIdOrderByCreatedAtDesc(owner.getId())).hasSize(1);
            throw new DataAccessResourceFailureException("synthetic database failure");
        });
        var completed = execute(new PersonalAiPersistenceService(failingUsage, results, transactions));
        assertThat(completed.persistenceStatus()).isEqualTo("UNCONFIRMED");
        assertThat(completed.id()).isNull();
        assertThat(completed.result()).isEqualTo("Synthetic completed output");
        assertThat(completed.inputTokens()).isEqualTo(21L);
        assertThat(completed.outputTokens()).isEqualTo(8L);
        assertThat(completed.warning()).contains("不要重复发送");
        verify(failingUsage, times(1)).save(any());
        assertNoWrites();
    }

    @Test void databaseConstraintFailureAtCommitRollsBackBothRecognitionAndUsage() {
        var failingUsage = mock(PersonalAiUsageRepository.class);
        when(failingUsage.save(any())).thenAnswer(call -> {
            results.flush();
            var row = (PersonalAiUsageEntity) call.getArgument(0);
            ReflectionTestUtils.setField(row, "action", "synthetic-action-exceeding-column-limit");
            // The real database rejects the row at transaction commit, outside this repository call.
            return usage.save(row);
        });
        var completed = execute(new PersonalAiPersistenceService(failingUsage, results, transactions));
        assertThat(completed.persistenceStatus()).isEqualTo("UNCONFIRMED");
        assertThat(completed.id()).isNull();
        verify(failingUsage, times(1)).save(any());
        assertNoWrites();
    }

    @Test void resultWriteFailureRollsBackBeforeUsageIsAttempted() {
        var failingResults = mock(PersonalRecognitionResultRepository.class);
        when(failingResults.save(any())).thenAnswer(call -> {
            results.saveAndFlush(call.getArgument(0));
            throw new DataAccessResourceFailureException("synthetic database failure");
        });
        var unusedUsage = mock(PersonalAiUsageRepository.class);
        var completed = execute(new PersonalAiPersistenceService(unusedUsage, failingResults, transactions));
        assertThat(completed.persistenceStatus()).isEqualTo("UNCONFIRMED");
        assertThat(completed.result()).isEqualTo("Synthetic completed output");
        verifyNoInteractions(unusedUsage);
        assertNoWrites();
    }

    @Test void lostCommitAcknowledgementLeavesTruthfulUnconfirmedResultWithoutReplaying() {
        var completed = execute(new PersonalAiPersistenceService(usage, results, lostCommitAcknowledgement()));
        assertThat(completed.persistenceStatus()).isEqualTo("UNCONFIRMED");
        assertThat(completed.id()).isNull();
        assertThat(completed.warning()).contains("未能确认").doesNotContain("synthetic-commit-ack");
        assertThat(results.findTop100ByOwnerIdOrderByCreatedAtDesc(owner.getId())).hasSize(1);
        assertThat(usage.findTop100ByOwnerIdOrderByCreatedAtDesc(owner.getId())).hasSize(1);
    }

    @Test void textUsageCommitAcknowledgementLossRetainsCompletedTextAndKnownTokens() {
        var sources = mock(NoteExperimentSourceService.class);
        when(sources.buildContext(any())).thenReturn("");
        var service = new PersonalAiService(current, settings,
                new PersonalAiPersistenceService(usage, results, lostCommitAcknowledgement()), encryption, endpoints, transport,
                new PersonalAiRateLimiter(), sources, true);
        var preview = service.preview(new PreviewRequest(AiProvider.OPENAI, "draft", "Synthetic", "Synthetic body", List.of()));
        var completed = service.execute(new ExecuteRequest(preview.previewToken(), true));
        assertThat(completed.persistenceStatus()).isEqualTo("UNCONFIRMED");
        assertThat(completed.result()).isEqualTo("Synthetic completed output");
        assertThat(completed.inputTokens()).isEqualTo(21L);
        assertThat(completed.outputTokens()).isEqualTo(8L);
        assertThat(completed.warning()).contains("用量记录", "不要重复发送");
        assertThat(usage.findTop100ByOwnerIdOrderByCreatedAtDesc(owner.getId())).hasSize(1);
        assertThatThrownBy(() -> service.execute(new ExecuteRequest(preview.previewToken(), true))).isInstanceOf(BusinessException.class);
        verify(transport).execute(any(), any(), any());
    }

    private PlatformTransactionManager lostCommitAcknowledgement() {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return transactions.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                transactions.commit(status);
                throw new TransactionSystemException("synthetic-commit-ack");
            }
            public void rollback(TransactionStatus status) { transactions.rollback(status); }
        };
    }
}
