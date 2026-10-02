package com.robustvision.platform.database;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.*;
import com.robustvision.platform.service.BillingService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs on H2 by default; MYSQL_TEST_URL opts into the same tests on fresh MySQL. */
@SpringBootTest(properties = {
        "app.bootstrap.enabled=false", "app.worker.enabled=false", "app.rate-limit.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=20", "spring.datasource.hikari.connection-timeout=15000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingConcurrencyIntegrationTest {
    @Autowired BillingService billing;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired WalletRepository wallets;
    @Autowired RechargeOrderRepository orders;
    @Autowired ProviderBudgetRepository budgets;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    private UserEntity operator;
    private TransactionTemplate tx;
    private static String schema;
    private static String adminUrl;
    private static String databaseUsername;
    private static String databasePassword;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String configured = System.getenv("MYSQL_TEST_URL");
        if (configured == null || configured.isBlank()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:billing_concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000;DATABASE_TO_LOWER=TRUE");
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
        schema = newSchema; // Only a successfully created database can be cleaned up.
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
    void prepareOperator() {
        tx = new TransactionTemplate(transactions);
        operator = createUser("ADMIN");
    }

    @Test
    void concurrentFirstAccessCreatesExactlyOneWallet() throws Exception {
        UserEntity user = createUser("RESEARCHER");
        List<Callable<Void>> operations = new ArrayList<>();
        for (int i = 0; i < 12; i++) operations.add(() -> { billing.assertCanRun(user); return null; });
        concurrently(operations);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_wallet WHERE user_id = ?", Long.class, user.getId())).isEqualTo(1);
        assertBalance(user, "20.0000", "0.0000");
    }

    @Test
    void differentOrdersCreditSameWalletWithoutLostUpdatesAndCannotSettleTwice() throws Exception {
        UserEntity user = createUser("RESEARCHER");
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String token = "fixture-" + UUID.randomUUID();
            tokens.add(token);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
            tx.executeWithoutResult(status -> orders.save(new RechargeOrderEntity(user, PaymentMethod.ALIPAY,
                    new BigDecimal("10.00"), RechargeStatus.PENDING_PAYMENT, null, null, null, hash,
                    "local-sandbox-fixture", Instant.now().plusSeconds(300))));
        }
        List<Callable<Void>> operations = tokens.stream().<Callable<Void>>map(token -> () -> {
            assertThat(billing.completeQrPayment(token).status()).isEqualTo(RechargeStatus.PAID);
            return null;
        }).toList();
        concurrently(operations);
        assertBalance(user, "100.0000", "0.0000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE user_id = ? AND type = 'RECHARGE'", Long.class, user.getId())).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recharge_order WHERE user_id = ? AND status = 'PAID'", Long.class, user.getId())).isEqualTo(8);
        assertThatThrownBy(() -> billing.completeQrPayment(tokens.get(0))).isInstanceOf(BusinessException.class);
        assertBalance(user, "100.0000", "0.0000");
    }

    @Test
    void concurrentAdminAdjustmentsPreserveEveryDeltaAndAuthorization() throws Exception {
        UserEntity user = createUser("RESEARCHER");
        billing.assertCanRun(user);
        List<Callable<Void>> operations = new ArrayList<>();
        for (int i = 0; i < 12; i++) operations.add(() -> {
            asUser(operator, () -> billing.adjustWallet(user.getId(),
                    new ApiDtos.AdjustWalletRequest(new BigDecimal("1.25"), null, "local concurrency fixture")));
            return null;
        });
        concurrently(operations);
        assertBalance(user, "35.0000", "0.0000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE user_id = ? AND type = 'ADMIN_ADJUST'", Long.class, user.getId())).isEqualTo(12);
        assertThatThrownBy(() -> asUser(user, () -> billing.adjustWallet(user.getId(),
                new ApiDtos.AdjustWalletRequest(BigDecimal.ONE, null, "not authorized"))))
                .isInstanceOf(BusinessException.class);
        assertBalance(user, "35.0000", "0.0000");
    }

    @Test
    void concurrentChargesPreservePerWalletAndSharedProviderTotalsIncludingLazyBudgetCreation() throws Exception {
        // Dedicated test database only. Exercise the missing-row path as well as
        // concurrent writes across different wallets to one shared provider.
        tx.executeWithoutResult(status -> budgets.deleteById(ModelProvider.DEEPSEEK));
        List<UserEntity> chargedUsers = List.of(createUser("RESEARCHER"), createUser("RESEARCHER"), createUser("RESEARCHER"));
        chargedUsers.forEach(billing::assertCanRun);
        List<Callable<Void>> operations = new ArrayList<>();
        for (UserEntity user : chargedUsers) {
            for (int i = 0; i < 4; i++) operations.add(() -> {
                assertThat(billing.recordUsage(user, ModelProvider.DEEPSEEK, 500_000, 0, UUID.randomUUID().toString()))
                        .isEqualByComparingTo("1.0000");
                return null;
            });
        }
        concurrently(operations);
        for (UserEntity user : chargedUsers) {
            assertBalance(user, "16.0000", "4.0000");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE user_id = ? AND type = 'USAGE'", Long.class, user.getId())).isEqualTo(4);
        }
        assertThat(budgets.findById(ModelProvider.DEEPSEEK).orElseThrow().getUsedCny()).isEqualByComparingTo("12.0000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_budget WHERE provider = 'DEEPSEEK'", Long.class)).isEqualTo(1);
    }

    private UserEntity createUser(String roleCode) {
        return tx.execute(status -> {
            RoleEntity role = roles.findByCode(roleCode).orElseGet(() -> roles.save(new RoleEntity(roleCode, roleCode, "test", Set.of())));
            String name = "billing-" + UUID.randomUUID();
            return users.save(new UserEntity(name, "synthetic-fixture", name, name + "@example.invalid", role));
        });
    }

    private void assertBalance(UserEntity user, String balance, String spent) {
        WalletEntity wallet = wallets.findByUserId(user.getId()).orElseThrow();
        assertThat(wallet.getBalanceCny()).isEqualByComparingTo(balance);
        assertThat(wallet.getMonthSpentCny()).isEqualByComparingTo(spent);
    }

    private static <T> T asUser(UserEntity user, Callable<T> action) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(), "unused", List.of()));
        SecurityContextHolder.setContext(context);
        try { return action.call(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private static void concurrently(List<Callable<Void>> operations) throws Exception {
        var pool = Executors.newFixedThreadPool(operations.size());
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = operations.stream().map(operation -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
                return operation.call();
            })).toList();
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(45, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
