package com.robustvision.platform.service;

import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.ApiDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboard-performance;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "app.worker.enabled=false"
})
@Transactional
class DashboardPerformanceTest {
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired DashboardService dashboard;
    @Autowired InferenceService inference;

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void summaryPreservesAggregatesVisibilityAndOnlyMaterializesFiveTasks() {
        seed(120);
        authenticate("owner");
        Measurement owner = measure(dashboard::summary);
        assertThat(owner.summary().totalTasks()).isEqualTo(90);
        assertThat(owner.summary().completedTasks()).isEqualTo(30);
        assertThat(owner.summary().failedTasks()).isEqualTo(30);
        assertThat(owner.summary().successRate()).isEqualTo(33.3333);
        assertThat(owner.summary().averageConfidenceLift()).isEqualTo(0.15);
        assertThat(owner.summary().recentTasks()).hasSize(5).allSatisfy(task ->
                assertThat(task.requestedBy()).isEqualTo("Owner"));
        assertThat(owner.taskLoads()).isEqualTo(5);
        assertThat(owner.statements()).isLessThanOrEqualTo(6);

        authenticate("admin");
        Measurement admin = measure(dashboard::summary);
        Measurement original = measure(this::originalSummary);
        assertThat(admin.summary()).isEqualTo(original.summary());
        assertThat(admin.taskLoads()).isEqualTo(5);
        assertThat(original.taskLoads()).isEqualTo(120);
        assertThat(admin.statements()).isLessThan(original.statements());
        System.out.printf(Locale.ROOT,
                "DASHBOARD_QUERY_COUNTS rows=120 original=%d optimized=%d taskLoads=%d->%d%n",
                original.statements(), admin.statements(), original.taskLoads(), admin.taskLoads());

        authenticate("empty");
        ApiDtos.DashboardSummary empty = measure(dashboard::summary).summary();
        assertThat(empty.totalTasks()).isZero();
        assertThat(empty.completedTasks()).isZero();
        assertThat(empty.failedTasks()).isZero();
        assertThat(empty.successRate()).isZero();
        assertThat(empty.averageConfidenceLift()).isZero();
        assertThat(empty.recentTasks()).isEmpty();
    }

    @Test
    void freshlyChangedPermissionIsUsedWithoutAnAuthorizationCache() {
        seed(12);
        authenticate("owner");
        assertThat(measure(dashboard::summary).summary().totalTasks()).isEqualTo(9);
        RoleEntity role = entityManager.createQuery("from RoleEntity r where r.code = 'RESEARCHER'", RoleEntity.class)
                .getSingleResult();
        role.setPermissions(Set.of("experiment:read:any"));
        entityManager.flush();
        assertThat(measure(dashboard::summary).summary().totalTasks()).isEqualTo(12);
        role = entityManager.find(RoleEntity.class, role.getId());
        role.setPermissions(Set.of("experiment:read"));
        entityManager.flush();
        assertThat(measure(dashboard::summary).summary().totalTasks()).isEqualTo(9);
    }

    @Test
    @EnabledIfSystemProperty(named = "performance.benchmark", matches = "true")
    void benchmarkOriginalAndBoundedDashboardWithIdenticalFixtures() {
        int rows = Integer.getInteger("performance.rows", 500);
        seed(rows);
        authenticate("admin");
        for (int i = 0; i < 5; i++) { measure(this::originalSummary); measure(dashboard::summary); }
        List<Measurement> before = new ArrayList<>();
        List<Measurement> after = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            // Alternate ordering to reduce one-sided JIT/GC/order bias.
            if (i % 2 == 0) { before.add(measure(this::originalSummary)); after.add(measure(dashboard::summary)); }
            else { after.add(measure(dashboard::summary)); before.add(measure(this::originalSummary)); }
        }
        assertThat(after.get(0).summary()).isEqualTo(before.get(0).summary());
        System.out.printf(Locale.ROOT,
                "DASHBOARD_BENCHMARK rows=%d warmup=5 samples=15 originalMedianMs=%.3f optimizedMedianMs=%.3f originalP95Ms=%.3f optimizedP95Ms=%.3f statements=%d->%d taskLoads=%d->%d%n",
                rows, percentile(before, 0.5), percentile(after, 0.5), percentile(before, 0.95), percentile(after, 0.95),
                before.get(0).statements(), after.get(0).statements(), before.get(0).taskLoads(), after.get(0).taskLoads());
    }

    private Measurement measure(Supplier<ApiDtos.DashboardSummary> action) {
        entityManager.flush();
        entityManager.clear();
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        long start = System.nanoTime();
        ApiDtos.DashboardSummary summary = action.get();
        long elapsed = System.nanoTime() - start;
        return new Measurement(summary, elapsed / 1_000_000.0, stats.getPrepareStatementCount(),
                stats.getEntityStatistics(InferenceTaskEntity.class.getName()).getLoadCount());
    }

    // Kept only in the test as the pre-optimization baseline: fetch every entity, convert every
    // JSON result/association, then aggregate in Java. Uses the original un-fetched JPQL query.
    private ApiDtos.DashboardSummary originalSummary() {
        UserEntity current = entityManager.createQuery("from UserEntity u where u.username = :username", UserEntity.class)
                .setParameter("username", SecurityContextHolder.getContext().getAuthentication().getName()).getSingleResult();
        boolean readAny = "ADMIN".equals(current.getRole().getCode()) || current.getRole().getPermissions().contains("experiment:read:any");
        var query = entityManager.createQuery("from InferenceTaskEntity t "
                + (readAny ? "" : "where t.requestedBy.id = :userId ")
                + "order by t.createdAt desc", InferenceTaskEntity.class);
        if (!readAny) query.setParameter("userId", current.getId());
        List<ApiDtos.InferenceView> tasks = query.getResultList().stream().map(inference::toView).toList();
        long completed = tasks.stream().filter(t -> t.status() == InferenceStatus.COMPLETED).count();
        long failed = tasks.stream().filter(t -> t.status() == InferenceStatus.FAILED).count();
        double lift = tasks.stream().filter(t -> t.baselineConfidence() != null && t.optimizedConfidence() != null)
                .mapToDouble(t -> t.optimizedConfidence() - t.baselineConfidence()).average().orElse(0);
        return new ApiDtos.DashboardSummary(tasks.size(), completed, failed,
                round(tasks.isEmpty() ? 0 : completed * 100.0 / tasks.size()), round(lift), tasks.stream().limit(5).toList());
    }

    private void seed(int rows) {
        RoleEntity adminRole = new RoleEntity("ADMIN", "Admin", "", Set.of("experiment:read:any"));
        RoleEntity role = new RoleEntity("RESEARCHER", "Researcher", "", Set.of("experiment:read"));
        entityManager.persist(adminRole); entityManager.persist(role);
        UserEntity admin = new UserEntity("admin", "unused", "Admin", "admin@perf.test", adminRole);
        UserEntity owner = new UserEntity("owner", "unused", "Owner", "owner@perf.test", role);
        UserEntity empty = new UserEntity("empty", "unused", "Empty", "empty@perf.test", role);
        entityManager.persist(admin); entityManager.persist(owner); entityManager.persist(empty);
        ModelDefinitionEntity model = new ModelDefinitionEntity("perf", "Performance", "1", ModelProvider.DEEPSEEK, TaskType.LICENSE_PLATE, "fixture");
        entityManager.persist(model);
        String payload = "{\"fixture\":\"" + "x".repeat(4096) + "\"}";
        for (int i = 0; i < rows; i++) {
            UserEntity user = i < rows * 3 / 4 ? owner : admin;
            FileAssetEntity file = new FileAssetEntity("fixture-" + i + ".png", "fixture-" + i, "image/png", 1,
                    "0".repeat(64), "fixture/" + i, user, FileSource.UPLOAD, FileScanStatus.CLEAN, "test");
            entityManager.persist(file);
            InferenceTaskEntity task = new InferenceTaskEntity(UUID.randomUUID().toString(), TaskType.LICENSE_PLATE, true, file, model, user);
            task.setStatus(i % 3 == 0 ? InferenceStatus.COMPLETED : i % 3 == 1 ? InferenceStatus.FAILED : InferenceStatus.PENDING);
            // Include nulls, negative lift and non-completed rows with both confidences.
            if (i % 3 != 2) task.setBaselineConfidence(0.5);
            task.setOptimizedConfidence(i % 3 == 0 ? 0.9 : 0.4);
            task.setBaselineResult(payload); task.setOptimizedResult(payload);
            org.springframework.test.util.ReflectionTestUtils.setField(task, "createdAt", Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i));
            entityManager.persist(task);
        }
        entityManager.flush(); entityManager.clear();
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, "", List.of()));
    }
    private double round(double value) { return Math.round(value * 10_000.0) / 10_000.0; }
    private double percentile(List<Measurement> values, double percentile) {
        double[] sorted = values.stream().mapToDouble(Measurement::milliseconds).sorted().toArray();
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1)];
    }
    private record Measurement(ApiDtos.DashboardSummary summary, double milliseconds, long statements, long taskLoads) {}
}
