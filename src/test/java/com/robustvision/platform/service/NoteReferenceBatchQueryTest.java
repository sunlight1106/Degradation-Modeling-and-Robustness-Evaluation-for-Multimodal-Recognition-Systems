package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.NoteReferenceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

/** The same correctness/query budgets run on H2 or opt-in fresh MySQL schemas. */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NoteReferenceService.class, NoteService.class, NoteShareService.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NoteReferenceBatchQueryTest {
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory factory;
    @Autowired NoteReferenceService references;
    @Autowired NoteService notes;
    @Autowired NoteShareService shares;
    @Autowired NoteReferenceRepository repository;
    @MockBean CurrentUserService currentUser;
    private Statistics statistics;
    private UserEntity owner;
    private int sequence;
    private static String schema;
    private static String adminUrl;
    private static String databaseUsername;
    private static String databasePassword;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String configured = System.getenv("MYSQL_TEST_URL");
        if (configured == null || configured.isBlank()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:note_reference_batch;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.flyway.enabled", () -> false);
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
            return;
        }
        int pathStart = configured.indexOf('/', "jdbc:mysql://".length());
        if (!configured.startsWith("jdbc:mysql://") || pathStart < 0) {
            throw new IllegalArgumentException("MYSQL_TEST_URL must be a single-server jdbc:mysql://host:port/database URL");
        }
        int queryStart = configured.indexOf('?', pathStart);
        String options = queryStart < 0 ? "" : configured.substring(queryStart);
        String server = configured.substring(0, pathStart + 1);
        adminUrl = server + options;
        databaseUsername = System.getenv().getOrDefault("MYSQL_TEST_USERNAME", "root");
        databasePassword = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        String newSchema = "rv_schema_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(adminUrl, databaseUsername, databasePassword);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + newSchema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        schema = newSchema;
        registry.add("spring.datasource.url", () -> server + schema + options);
        registry.add("spring.datasource.username", () -> databaseUsername);
        registry.add("spring.datasource.password", () -> databasePassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @AfterAll
    static void removeOnlyOurDisposableDatabase() throws Exception {
        if (schema != null) {
            try (var connection = DriverManager.getConnection(adminUrl, databaseUsername, databasePassword);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE `" + schema + "`");
            }
        }
    }

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString();
        RoleEntity role = new RoleEntity("ref-" + suffix, "Test", null, Set.of("note:read"));
        em.persist(role);
        owner = new UserEntity("ref-" + suffix, "synthetic-fixture", "Owner", suffix + "@example.invalid", role);
        em.persist(owner);
        when(currentUser.requireCurrent()).thenReturn(owner);
        statistics = factory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void mixedReferencesPreserveCompleteViewsOrderLabelsAndMissingTargetsInFourQueries() {
        NoteEntity note = seedMixed(60);
        for (NoteReferenceType type : NoteReferenceType.values()) {
            ref(note, type, UUID.randomUUID().toString(), "", -1);
            ref(note, type, UUID.randomUUID().toString(), "保留的自定义标题", -1);
        }
        NoteEntity anotherNote = note("unrelated");
        ref(anotherNote, NoteReferenceType.FILE, file(1).getId(), "not visible", -100);
        Measurement before = measure(() -> originalResolve(note.getId()));
        Measurement after = measure(() -> references.resolveForNote(note.getId()));
        assertThat(after.views()).containsExactlyElementsOf(before.views());
        assertThat(after.views()).hasSize(66);
        assertThat(after.views().subList(0, 6)).allMatch(view -> !view.accessible());
        assertThat(after.statements()).isEqualTo(4);
        assertThat(after.entityLoads()).isZero();
        assertThat(before.statements()).isGreaterThan(60);
        System.out.printf(Locale.ROOT, "NOTE_REFERENCE_QUERY_COUNTS refs=66 statements=%d->%d entityLoads=%d->%d%n",
                before.statements(), after.statements(), before.entityLoads(), after.entityLoads());
    }

    @Test
    void emptyNoteDoesNotQueryTargets() {
        NoteEntity note = note("empty");
        Measurement result = measure(() -> references.resolveForNote(note.getId()));
        assertThat(result.views()).isEmpty();
        assertThat(result.statements()).isEqualTo(1);
        assertThat(result.entityLoads()).isZero();
    }

    @Test
    void batchBoundaryRemainsCompleteWithoutEntityHydration() {
        NoteEntity note = note("501 references");
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            NoteReferenceEntity reference = ref(note, NoteReferenceType.FILE, file(i).getId(), "file " + i, -i);
            expected.add(0, reference.getId());
        }
        Measurement result = measure(() -> references.resolveForNote(note.getId()));
        assertThat(result.views()).extracting(ApiDtos.NoteReferenceView::id).containsExactlyElementsOf(expected);
        assertThat(result.views()).allMatch(ApiDtos.NoteReferenceView::accessible);
        assertThat(result.statements()).isEqualTo(3);
        assertThat(result.entityLoads()).isZero();
    }

    @Test
    void updatesAndDeletionAreVisibleOnNextCallAndNoteOwnershipStillApplies() {
        NoteEntity note = note("private");
        FileAssetEntity file = file(1);
        ref(note, NoteReferenceType.FILE, file.getId(), null, 0);
        assertThat(measure(() -> references.resolveForNote(note.getId())).views().get(0).accessible()).isTrue();
        em.createQuery("update FileAssetEntity f set f.originalName = :name where f.id = :id")
                .setParameter("name", "renamed.png").setParameter("id", file.getId()).executeUpdate();
        assertThat(measure(() -> references.resolveForNote(note.getId())).views().get(0).displayTitle()).isEqualTo("renamed.png");
        em.createQuery("delete from FileAssetEntity f where f.id = :id").setParameter("id", file.getId()).executeUpdate();
        ApiDtos.NoteReferenceView deleted = measure(() -> references.resolveForNote(note.getId())).views().get(0);
        assertThat(deleted.accessible()).isFalse();
        assertThat(deleted.displayTitle()).isEqualTo("已删除的文件");
        assertThat(notes.get(note.getId()).references()).containsExactly(deleted);
        UserEntity other = new UserEntity("other-" + UUID.randomUUID(), "fixture", "Other", UUID.randomUUID() + "@example.invalid",
                em.find(UserEntity.class, owner.getId()).getRole());
        em.persist(other);
        when(currentUser.requireCurrent()).thenReturn(other);
        assertThatThrownBy(() -> notes.get(note.getId())).isInstanceOf(BusinessException.class).hasMessage("笔记不存在或无权访问");
    }

    @Test
    void addAndRemoveResponsesSeeTheSameTransactionChanges() {
        NoteEntity note = note("editing");
        FileAssetEntity file = file(1);
        ApiDtos.NoteView added = notes.addReference(note.getId(),
                new ApiDtos.AddNoteReferenceRequest("FILE", file.getId(), " custom label "));
        assertThat(added.references()).hasSize(1);
        ApiDtos.NoteReferenceView reference = added.references().get(0);
        assertThat(reference.displayTitle()).isEqualTo("custom label");
        assertThat(reference.accessible()).isTrue();
        assertThat(notes.removeReference(note.getId(), reference.id()).references()).isEmpty();
        assertThat(measure(() -> references.resolveForNote(note.getId())).views()).isEmpty();
    }

    @Test
    void sharedNoteKeepsResolvedReferencesAndRejectsRevokedAndExpiredTokens() {
        NoteEntity note = seedMixed(6);
        String token = UUID.randomUUID().toString().replace("-", "");
        NoteShareEntity share = new NoteShareEntity(note, owner, token, "fixture", null);
        em.persist(share);
        List<ApiDtos.NoteReferenceView> expected = measure(() -> originalResolve(note.getId())).views();
        assertThat(shares.readByToken(token).references()).containsExactlyElementsOf(expected);
        assertThat(em.find(NoteShareEntity.class, share.getId()).getViewCount()).isEqualTo(1);
        em.find(NoteShareEntity.class, share.getId()).revoke(Instant.now());
        em.flush();
        em.clear();
        assertThatThrownBy(() -> shares.readByToken(token)).isInstanceOf(BusinessException.class)
                .hasMessage("该分享链接已过期或被撤销");
        String expiredToken = UUID.randomUUID().toString().replace("-", "");
        em.persist(new NoteShareEntity(em.find(NoteEntity.class, note.getId()), em.find(UserEntity.class, owner.getId()),
                expiredToken, "expired", Instant.parse("2020-01-01T00:00:00Z")));
        em.flush();
        em.clear();
        assertThatThrownBy(() -> shares.readByToken(expiredToken)).isInstanceOf(BusinessException.class)
                .hasMessage("该分享链接已过期或被撤销");
    }

    @Test
    void mysqlCaseInsensitiveTargetIdsRetainDatabaseComparisonSemantics() {
        assumeTrue(schema != null, "Case-insensitive CHAR/VARCHAR comparison is a MySQL contract");
        NoteEntity note = seedMixed(3);
        em.flush();
        em.createQuery("update NoteReferenceEntity r set r.referenceId = upper(r.referenceId) where r.note.id = :id")
                .setParameter("id", note.getId()).executeUpdate();
        Measurement before = measure(() -> originalResolve(note.getId()));
        Measurement after = measure(() -> references.resolveForNote(note.getId()));
        assertThat(after.views()).containsExactlyElementsOf(before.views());
        assertThat(after.views()).allMatch(ApiDtos.NoteReferenceView::accessible);
    }

    @Test
    @EnabledIfSystemProperty(named = "performance.benchmark", matches = "true")
    void benchmarkOriginalAndBatchedResolutionWithIdenticalFixtures() {
        int rows = Integer.getInteger("performance.rows", 500);
        NoteEntity note = seedMixed(rows);
        for (int i = 0; i < 5; i++) {
            measure(() -> originalResolve(note.getId()));
            measure(() -> references.resolveForNote(note.getId()));
        }
        List<Measurement> before = new ArrayList<>();
        List<Measurement> after = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            if (i % 2 == 0) {
                before.add(measure(() -> originalResolve(note.getId())));
                after.add(measure(() -> references.resolveForNote(note.getId())));
            } else {
                after.add(measure(() -> references.resolveForNote(note.getId())));
                before.add(measure(() -> originalResolve(note.getId())));
            }
            assertThat(after.get(i).views()).containsExactlyElementsOf(before.get(i).views());
        }
        System.out.printf(Locale.ROOT,
                "NOTE_REFERENCE_BENCHMARK database=%s refs=%d warmup=5 samples=15 originalMedianMs=%.3f optimizedMedianMs=%.3f originalP95Ms=%.3f optimizedP95Ms=%.3f statements=%d->%d entityLoads=%d->%d%n",
                schema == null ? "H2" : "MySQL", rows, percentile(before, 0.5), percentile(after, 0.5),
                percentile(before, 0.95), percentile(after, 0.95), before.get(0).statements(), after.get(0).statements(),
                before.get(0).entityLoads(), after.get(0).entityLoads());
    }

    private NoteEntity seedMixed(int rows) {
        NoteEntity note = note("mixed references");
        for (int i = 0; i < rows; i++) {
            String label = switch (i % 4) { case 0 -> null; case 1 -> ""; case 2 -> "  "; default -> "自定义 " + i; };
            NoteReferenceType type = NoteReferenceType.values()[i % 3];
            String id;
            switch (type) {
                case FILE -> id = file(i).getId();
                case TASK -> {
                    ModelDefinitionEntity model = new ModelDefinitionEntity("model-" + sequence++, "模型 " + i, "1",
                            ModelProvider.DEEPSEEK, TaskType.LICENSE_PLATE, "fixture");
                    em.persist(model);
                    InferenceTaskEntity task = new InferenceTaskEntity(UUID.randomUUID().toString(), TaskType.LICENSE_PLATE,
                            true, file(i), model, owner);
                    task.setStatus(InferenceStatus.values()[i % InferenceStatus.values().length]);
                    if (i % 2 == 0) task.setUsage(10, 20, new BigDecimal("0.123456"));
                    task.setBaselineResult("{\"fixture\":\"" + "x".repeat(4096) + "\"}");
                    task.setOptimizedResult(task.getBaselineResult());
                    em.persist(task);
                    id = task.getId();
                }
                case ENTRY -> {
                    KnowledgeTopicEntity topic = new KnowledgeTopicEntity("领域 " + i, "主题 " + i, null, false, owner, i);
                    em.persist(topic);
                    KnowledgeEntryEntity entry = new KnowledgeEntryEntity(topic, "知识卡 " + i, null, "body".repeat(1024), null, false, owner, i);
                    em.persist(entry);
                    id = entry.getId();
                }
                default -> throw new IllegalStateException("Unexpected reference type");
            }
            ref(note, type, id, label, i / 2); // Ties must retain the reference ID tiebreaker.
        }
        return note;
    }

    private FileAssetEntity file(int i) {
        long size = switch (i % 3) { case 0 -> 37; case 1 -> 2500; default -> 3_145_728; };
        FileAssetEntity file = new FileAssetEntity("文件 " + i + ".png", "fixture-" + sequence++, "image/png", size,
                "a".repeat(64), "fixture/" + sequence, owner, FileSource.UPLOAD, FileScanStatus.CLEAN, "test");
        em.persist(file);
        return file;
    }

    private NoteEntity note(String title) {
        NoteEntity note = new NoteEntity(owner, title, "body", null, NoteStatus.ACTIVE);
        em.persist(note);
        return note;
    }

    private NoteReferenceEntity ref(NoteEntity note, NoteReferenceType type, String id, String label, int order) {
        NoteReferenceEntity reference = new NoteReferenceEntity(note, type, id, label, order);
        em.persist(reference);
        return reference;
    }

    private Measurement measure(Supplier<List<ApiDtos.NoteReferenceView>> action) {
        em.flush();
        em.clear();
        statistics.clear();
        long start = System.nanoTime();
        List<ApiDtos.NoteReferenceView> views = action.get();
        return new Measurement(views, (System.nanoTime() - start) / 1_000_000.0,
                statistics.getPrepareStatementCount(), statistics.getEntityLoadCount());
    }

    // Exact pre-optimization lookup/conversion behavior kept only as a regression and timing baseline.
    private List<ApiDtos.NoteReferenceView> originalResolve(String noteId) {
        return repository.findByNoteIdOrderBySortOrderAscIdAsc(noteId).stream().map(reference -> {
            String title;
            String meta;
            boolean accessible;
            switch (reference.getReferenceType()) {
                case FILE -> {
                    FileAssetEntity entity = em.find(FileAssetEntity.class, reference.getReferenceId());
                    accessible = entity != null;
                    title = entity != null ? entity.getOriginalName() : "已删除的文件";
                    meta = entity != null ? entity.getContentType() + " · " + formatSize(entity.getSizeBytes()) + " · SHA-256 "
                            + shortHash(entity.getSha256()) : "引用目标已被删除";
                }
                case TASK -> {
                    InferenceTaskEntity entity = em.find(InferenceTaskEntity.class, reference.getReferenceId());
                    accessible = entity != null;
                    title = entity != null ? "推理任务 " + entity.getTraceId() : "已删除的推理任务";
                    meta = entity != null ? entity.getModel().getName() + " · " + entity.getStatus()
                            + (entity.getCostCny() != null ? " · ¥" + entity.getCostCny() : "")
                            + " · traceId " + entity.getTraceId() : "引用目标已被删除";
                }
                case ENTRY -> {
                    KnowledgeEntryEntity entity = em.find(KnowledgeEntryEntity.class, reference.getReferenceId());
                    accessible = entity != null;
                    title = entity != null ? entity.getTitle() : "已删除的知识卡";
                    meta = entity != null ? entity.getTopic().getDomain() + " · " + entity.getTopic().getName() : "引用目标已被删除";
                }
                default -> throw new IllegalStateException("Unexpected reference type");
            }
            String label = reference.getLabel();
            if (label != null && !label.isBlank()) title = label;
            return new ApiDtos.NoteReferenceView(reference.getId(), reference.getReferenceType().name(), reference.getReferenceId(),
                    label, title, meta, accessible);
        }).toList();
    }

    private String shortHash(String hash) {
        return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12) + "…";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private double percentile(List<Measurement> measurements, double percentile) {
        double[] sorted = measurements.stream().mapToDouble(Measurement::milliseconds).sorted().toArray();
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1)];
    }

    private record Measurement(List<ApiDtos.NoteReferenceView> views, double milliseconds, long statements, long entityLoads) {}
}
