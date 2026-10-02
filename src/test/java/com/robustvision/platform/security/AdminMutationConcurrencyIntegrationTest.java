package com.robustvision.platform.security;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.*;
import com.robustvision.platform.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real concurrent transactions on H2 by default, or a disposable MySQL schema when opted in. */
@SpringBootTest(properties = {"app.bootstrap.enabled=false", "app.worker.enabled=false", "app.rate-limit.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=8", "spring.datasource.hikari.connection-timeout=15000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminMutationConcurrencyIntegrationTest {
    @Autowired UserService service;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    private UserEntity a, b, target;
    private RoleEntity adminRole, writerRole, viewerRole;
    private TransactionTemplate tx;
    private static String schema, adminUrl, databaseUsername, databasePassword;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        String configured = System.getenv("MYSQL_TEST_URL");
        if (configured == null || configured.isBlank()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:admin_guard_concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000;DATABASE_TO_LOWER=TRUE");
            registry.add("spring.datasource.username", () -> "sa"); registry.add("spring.datasource.password", () -> "");
            registry.add("spring.flyway.enabled", () -> false); registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
            return;
        }
        int pathStart = configured.indexOf('/', "jdbc:mysql://".length());
        if (!configured.startsWith("jdbc:mysql://") || pathStart < 0) throw new IllegalArgumentException("MYSQL_TEST_URL must use a single MySQL server");
        int queryStart = configured.indexOf('?', pathStart);
        String options = queryStart < 0 ? "" : configured.substring(queryStart), server = configured.substring(0, pathStart + 1);
        adminUrl = server + options;
        databaseUsername = System.getenv().getOrDefault("MYSQL_TEST_USERNAME", "root");
        databasePassword = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        String disposable = "rv_schema_test_admin_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(adminUrl, databaseUsername, databasePassword); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + disposable + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        schema = disposable;
        registry.add("spring.datasource.url", () -> server + schema + options);
        registry.add("spring.datasource.username", () -> databaseUsername); registry.add("spring.datasource.password", () -> databasePassword);
        registry.add("spring.flyway.enabled", () -> true); registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
    @AfterAll static void removeOnlyDisposableSchema() throws Exception {
        if (schema != null) try (var connection = DriverManager.getConnection(adminUrl, databaseUsername, databasePassword); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + schema + "`");
        }
    }
    @BeforeEach void fixtures() {
        tx = new TransactionTemplate(transactions);
        // Only synthetic fixtures in this class's private database are removed.
        jdbc.update("DELETE FROM user_session"); jdbc.update("DELETE FROM app_user");
        adminRole = roles.findByCode("ADMIN").orElseGet(() -> roles.save(new RoleEntity("ADMIN", "Admin", "", Permissions.allCodes())));
        writerRole = roles.findByCode("DELEGATED_WRITER").orElseGet(() -> roles.save(new RoleEntity("DELEGATED_WRITER", "Writer", "", Set.of("user:write", "role:write"))));
        viewerRole = roles.findByCode("GUARD_VIEWER").orElseGet(() -> roles.save(new RoleEntity("GUARD_VIEWER", "Viewer", "", Set.of())));
        a = user("admin-a", adminRole); b = user("admin-b", adminRole); target = user("target", viewerRole);
    }
    private UserEntity user(String name, RoleEntity role) {
        return users.save(new UserEntity(name, "synthetic-non-login-hash", name, name + "@example.invalid", role));
    }
    @Test void concurrentMutualDisableLeavesExactlyOneActiveAdministrator() throws Exception {
        List<String> result = concurrent(
                () -> as(a, () -> service.update(b.getId(), update(UserStatus.DISABLED, null))),
                () -> as(b, () -> service.update(a.getId(), update(UserStatus.DISABLED, null))));
        assertThat(result).containsExactlyInAnyOrder("OK", "ACCOUNT_DISABLED");
        assertThat(users.countByRoleCodeAndStatus("ADMIN", UserStatus.ACTIVE)).isEqualTo(1);
    }
    @Test void concurrentMutualDemotionLeavesExactlyOneActiveAdministrator() throws Exception {
        List<String> result = concurrent(
                () -> as(a, () -> service.update(b.getId(), update(null, writerRole.getId()))),
                () -> as(b, () -> service.update(a.getId(), update(null, writerRole.getId()))));
        assertThat(result).containsExactlyInAnyOrder("OK", "ADMIN_REQUIRED");
        assertThat(users.countByRoleCodeAndStatus("ADMIN", UserStatus.ACTIVE)).isEqualTo(1);
    }
    @Test void waitingDisabledOperatorCannotUsePreviouslyLoadedEntity() throws Exception {
        staleOperatorAttempt(update(UserStatus.DISABLED, null),
                () -> service.update(target.getId(), new ApiDtos.UpdateUserRequest("forbidden change", null, null, null, null)), "ACCOUNT_DISABLED");
        assertThat(users.findById(target.getId()).orElseThrow().getDisplayName()).isEqualTo("target");
    }
    @Test void waitingDemotedOperatorCannotChangeRolePermissionsFromCachedAdminState() throws Exception {
        staleOperatorAttempt(update(null, writerRole.getId()),
                () -> service.updatePermissions(viewerRole.getId(), Permissions.allCodes()), "ADMIN_REQUIRED");
        assertThat(roles.findById(viewerRole.getId()).orElseThrow().getPermissions()).isEmpty();
    }
    @Test void disabledOperatorCannotCreateUsersOrRoles() throws Exception {
        as(a, () -> service.update(b.getId(), update(UserStatus.DISABLED, null)));
        assertThat(outcome(() -> as(b, () -> service.create(new ApiDtos.CreateUserRequest(
                "forbidden-user", "SyntheticPassword123!", "Forbidden", "forbidden@example.invalid", adminRole.getId()))))).isEqualTo("ACCOUNT_DISABLED");
        assertThat(outcome(() -> as(b, () -> service.createRole(new ApiDtos.CreateRoleRequest(
                "FORBIDDEN_ROLE", "Forbidden", "", Set.of("user:write")))))).isEqualTo("ACCOUNT_DISABLED");
        assertThat(users.existsByUsername("forbidden-user")).isFalse(); assertThat(roles.existsByCode("FORBIDDEN_ROLE")).isFalse();
    }
    private void staleOperatorAttempt(ApiDtos.UpdateUserRequest modification, Callable<?> forbiddenAction, String expected) throws Exception {
        CountDownLatch guardHeld = new CountDownLatch(1), staleLoaded = new CountDownLatch(1), mutationStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> outcome(() -> as(a, () -> tx.execute(status -> {
                roles.lockAdminGuard().orElseThrow(); guardHeld.countDown(); await(staleLoaded); await(mutationStarted);
                return service.update(b.getId(), modification);
            }))));
            Future<String> second = pool.submit(() -> outcome(() -> as(b, () -> tx.execute(status -> {
                await(guardHeld);
                UserEntity stale = users.findById(b.getId()).orElseThrow();
                assertThat(stale.getStatus()).isEqualTo(UserStatus.ACTIVE); assertThat(stale.getRole().getCode()).isEqualTo("ADMIN");
                staleLoaded.countDown(); mutationStarted.countDown();
                try { return forbiddenAction.call(); } catch (RuntimeException exception) { throw exception; }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }))));
            assertThat(first.get(25, TimeUnit.SECONDS)).isEqualTo("OK");
            assertThat(second.get(25, TimeUnit.SECONDS)).isEqualTo(expected);
        } finally { pool.shutdownNow(); }
    }
    @SafeVarargs private final List<String> concurrent(Callable<?>... operations) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(operations.length);
        CountDownLatch ready = new CountDownLatch(operations.length), start = new CountDownLatch(1);
        try {
            var futures = Arrays.stream(operations).map(operation -> pool.submit(() -> {
                ready.countDown(); await(start); return outcome(operation);
            })).toList();
            await(ready); start.countDown(); List<String> results = new ArrayList<>();
            for (Future<String> future : futures) results.add(future.get(25, TimeUnit.SECONDS));
            return results;
        } finally { pool.shutdownNow(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent fixture timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
    private static String outcome(Callable<?> operation) throws Exception {
        try { operation.call(); return "OK"; } catch (BusinessException expected) { return expected.getCode(); }
    }
    private static <T> T as(UserEntity user, Callable<T> operation) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(), "unused", List.of()));
        SecurityContextHolder.setContext(context);
        try { return operation.call(); } finally { SecurityContextHolder.clearContext(); }
    }
    private static ApiDtos.UpdateUserRequest update(UserStatus status, Long roleId) {
        return new ApiDtos.UpdateUserRequest(null, null, null, status, roleId);
    }
}
