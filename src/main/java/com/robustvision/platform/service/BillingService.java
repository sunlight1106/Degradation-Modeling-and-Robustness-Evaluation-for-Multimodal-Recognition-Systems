package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class BillingService {
    private final WalletRepository walletRepository;
    private final WalletLedgerRepository ledgerRepository;
    private final RechargeOrderRepository rechargeRepository;
    private final ProviderBudgetRepository providerBudgetRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final ProviderKeyRingService keyRing;
    private final ProviderBalanceService providerBalanceService;
    private final SmsGatewayService smsGateway;
    private final BigDecimal defaultBalance;
    private final BigDecimal defaultQuota;
    private final String publicWebUrl;
    private final SecureRandom random = new SecureRandom();
    private final Map<ModelProvider, Price> prices = new EnumMap<>(ModelProvider.class);

    public BillingService(WalletRepository walletRepository, WalletLedgerRepository ledgerRepository,
                          RechargeOrderRepository rechargeRepository, ProviderBudgetRepository providerBudgetRepository,
                          UserRepository userRepository, CurrentUserService currentUserService,
                          ProviderKeyRingService keyRing, ProviderBalanceService providerBalanceService,
                          SmsGatewayService smsGateway,
                          @Value("${app.billing.default-balance-cny:20.00}") BigDecimal defaultBalance,
                          @Value("${app.billing.default-monthly-quota-cny:100.00}") BigDecimal defaultQuota,
                          @Value("${app.payment.public-web-url:http://localhost:4173}") String publicWebUrl,
                          @Value("${app.model.deepseek.input-price-cny-per-million:2.00}") BigDecimal dsIn,
                          @Value("${app.model.deepseek.output-price-cny-per-million:8.00}") BigDecimal dsOut,
                          @Value("${app.model.kimi.input-price-cny-per-million:4.00}") BigDecimal kimiIn,
                          @Value("${app.model.kimi.output-price-cny-per-million:16.00}") BigDecimal kimiOut,
                          @Value("${app.model.qwen.input-price-cny-per-million:3.00}") BigDecimal qwenIn,
                          @Value("${app.model.qwen.output-price-cny-per-million:9.00}") BigDecimal qwenOut) {
        this.walletRepository = walletRepository; this.ledgerRepository = ledgerRepository;
        this.rechargeRepository = rechargeRepository; this.providerBudgetRepository = providerBudgetRepository;
        this.userRepository = userRepository; this.currentUserService = currentUserService; this.keyRing = keyRing;
        this.providerBalanceService = providerBalanceService; this.smsGateway = smsGateway;
        this.defaultBalance = defaultBalance; this.defaultQuota = defaultQuota;
        this.publicWebUrl = publicWebUrl.replaceAll("/+$", "");
        prices.put(ModelProvider.DEEPSEEK, new Price(dsIn, dsOut));
        prices.put(ModelProvider.KIMI, new Price(kimiIn, kimiOut));
        prices.put(ModelProvider.QWEN, new Price(qwenIn, qwenOut));
        prices.put(ModelProvider.DEMO, new Price(BigDecimal.ZERO, BigDecimal.ZERO));
        prices.put(ModelProvider.CUSTOM, new Price(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Transactional
    public void assertCanRun(UserEntity user) {
        WalletEntity wallet = wallet(user); wallet.resetPeriodIfNeeded(LocalDate.now());
        if (wallet.getBalanceCny().compareTo(new BigDecimal("0.01")) < 0)
            throw new BusinessException(HttpStatus.PAYMENT_REQUIRED, "WALLET_BALANCE_LOW", "余额不足，请先充值");
        if (wallet.getMonthSpentCny().compareTo(wallet.getMonthlyQuotaCny()) >= 0)
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "MONTHLY_QUOTA_EXCEEDED", "本月调用配额已用完");
        walletRepository.save(wallet);
    }

    @Transactional
    public BigDecimal recordUsage(UserEntity user, ModelProvider provider, long inputTokens, long outputTokens, String referenceId) {
        Price price = prices.getOrDefault(provider, new Price(BigDecimal.ZERO, BigDecimal.ZERO));
        BigDecimal cost = price.input().multiply(BigDecimal.valueOf(inputTokens))
                .add(price.output().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        WalletEntity wallet = wallet(user); wallet.resetPeriodIfNeeded(LocalDate.now());
        BigDecimal charged = cost.min(wallet.getBalanceCny()); wallet.debit(charged); walletRepository.save(wallet);
        ledgerRepository.save(new WalletLedgerEntity(user, "USAGE", charged.negate(), wallet.getBalanceCny(), referenceId,
                keyRing.displayName(provider) + " 模型调用"));
        ProviderBudgetEntity budget = providerBudgetRepository.findById(provider)
                .orElseGet(() -> new ProviderBudgetEntity(provider, new BigDecimal("500.00")));
        budget.addUsage(cost); providerBudgetRepository.save(budget); return charged;
    }

    @Transactional
    public ApiDtos.BillingSummary summary() {
        UserEntity user = currentUserService.requireCurrent(); WalletEntity wallet = wallet(user);
        wallet.resetPeriodIfNeeded(LocalDate.now()); walletRepository.save(wallet);
        return new ApiDtos.BillingSummary(toWallet(wallet),
                rechargeRepository.findTop10ByUserIdOrderByCreatedAtDesc(user.getId()).stream().map(order -> toOrder(order, null, null)).toList(),
                ledgerRepository.findTop12ByUserIdOrderByCreatedAtDesc(user.getId()).stream().map(this::toLedger).toList());
    }

    @Transactional
    public ApiDtos.RechargeOrderView createRecharge(ApiDtos.CreateRechargeRequest request) {
        UserEntity user = currentUserService.requireCurrent();
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ONE) < 0 || amount.compareTo(new BigDecimal("10000")) > 0)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RECHARGE_AMOUNT_INVALID", "充值金额需在 1 到 10000 元之间");
        if (request.method() == PaymentMethod.BANK_CARD) {
            String phone = request.phone() == null ? "" : request.phone().trim();
            if (!phone.matches("^1[3-9]\\d{9}$")) throw new BusinessException(HttpStatus.BAD_REQUEST, "PHONE_INVALID", "请输入有效手机号");
            String code = "%06d".formatted(random.nextInt(1_000_000));
            RechargeOrderEntity order = rechargeRepository.save(new RechargeOrderEntity(user, request.method(), amount,
                    RechargeStatus.PENDING_VERIFICATION, normalizeLast4(request.bankLast4()), maskPhone(phone), hash(code),
                    null, null, Instant.now().plusSeconds(600)));
            smsGateway.send(phone, code, order.getId());
            return toOrder(order, smsGateway.sandbox() ? code : null, null);
        }
        String token = randomToken();
        String payload = publicWebUrl + "/pay/" + token;
        RechargeOrderEntity order = rechargeRepository.save(new RechargeOrderEntity(user, request.method(), amount,
                RechargeStatus.PENDING_PAYMENT, null, null, null, hash(token), payload, Instant.now().plusSeconds(900)));
        return toOrder(order, null, token);
    }

    @Transactional
    public ApiDtos.RechargeOrderView recharge(String id) {
        UserEntity user = currentUserService.requireCurrent();
        RechargeOrderEntity order = rechargeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "RECHARGE_NOT_FOUND", "充值订单不存在"));
        if (!order.getUser().getId().equals(user.getId()) && !currentUserService.isSuperAdmin(user))
            throw new BusinessException(HttpStatus.FORBIDDEN, "RECHARGE_ACCESS_DENIED", "无权查看此订单");
        expireForRead(order);
        return toOrder(order, null, null);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ApiDtos.RechargeOrderView confirmRecharge(String id, ApiDtos.ConfirmRechargeRequest request) {
        UserEntity user = currentUserService.requireCurrent();
        RechargeOrderEntity order = rechargeRepository.findLockedById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "RECHARGE_NOT_FOUND", "充值订单不存在"));
        if (!order.getUser().getId().equals(user.getId())) throw new BusinessException(HttpStatus.FORBIDDEN, "RECHARGE_ACCESS_DENIED", "无权操作此订单");
        requirePending(order, RechargeStatus.PENDING_VERIFICATION); expireIfNeeded(order);
        if (!hash(request.verificationCode().trim()).equals(order.getVerificationHash())) {
            order.recordInvalidCode(); rechargeRepository.save(order);
            int remaining = Math.max(0, 5 - order.getVerificationAttempts());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_INVALID",
                    remaining == 0 ? "验证码连续错误 5 次，订单已锁定" : "验证码不正确，还可尝试 " + remaining + " 次");
        }
        settle(order, "银行卡短信验证沙箱充值（未发生真实扣款）");
        return toOrder(order, null, null);
    }

    @Transactional
    public ApiDtos.PublicPaymentView publicPayment(String token) {
        RechargeOrderEntity order = rechargeRepository.findByPaymentTokenHash(hash(token))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "支付二维码无效"));
        expireForRead(order);
        return toPublic(order);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ApiDtos.PublicPaymentView completeQrPayment(String token) {
        RechargeOrderEntity order = rechargeRepository.findLockedByPaymentTokenHash(hash(token))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "支付二维码无效"));
        requirePending(order, RechargeStatus.PENDING_PAYMENT); expireIfNeeded(order);
        settle(order, (order.getMethod() == PaymentMethod.ALIPAY ? "支付宝" : "微信") + "扫码沙箱充值（未发生真实扣款）");
        return toPublic(order);
    }

    @Transactional
    public List<ApiDtos.ProviderBudgetView> providerBudgets() {
        return List.of(ModelProvider.DEEPSEEK, ModelProvider.KIMI, ModelProvider.QWEN).stream().map(this::providerView).toList();
    }

    @Transactional
    public ApiDtos.ProviderBudgetView updateProviderBudget(ModelProvider provider, ApiDtos.UpdateProviderBudgetRequest request) {
        currentUserService.requireSuperAdmin();
        ProviderBudgetEntity budget = providerBudgetRepository.findById(provider)
                .orElseGet(() -> new ProviderBudgetEntity(provider, request.monthlyBudgetCny()));
        budget.setMonthlyBudgetCny(request.monthlyBudgetCny()); providerBudgetRepository.save(budget); return providerView(provider);
    }

    @Transactional
    public List<ApiDtos.AdminWalletView> adminWallets() {
        currentUserService.requireSuperAdmin();
        return userRepository.findAllByOrderByCreatedAtDesc().stream().map(user ->
                new ApiDtos.AdminWalletView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().getCode(), toWallet(wallet(user)))).toList();
    }

    @Transactional
    public ApiDtos.AdminWalletView adjustWallet(Long userId, ApiDtos.AdjustWalletRequest request) {
        UserEntity operator = currentUserService.requireCurrent(); currentUserService.requireSuperAdmin();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        WalletEntity wallet = wallet(user); BigDecimal previousBalance = wallet.getBalanceCny(); wallet.adjustBalance(request.balanceDelta());
        if (request.monthlyQuotaCny() != null) wallet.setMonthlyQuotaCny(request.monthlyQuotaCny());
        walletRepository.save(wallet);
        BigDecimal actualDelta = wallet.getBalanceCny().subtract(previousBalance);
        ledgerRepository.save(new WalletLedgerEntity(user, "ADMIN_ADJUST", actualDelta, wallet.getBalanceCny(),
                operator.getUsername(), "管理员调度：" + request.reason().trim()));
        return new ApiDtos.AdminWalletView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().getCode(), toWallet(wallet));
    }

    private ApiDtos.ProviderBudgetView providerView(ModelProvider provider) {
        ProviderBudgetEntity budget = providerBudgetRepository.findById(provider)
                .orElseGet(() -> providerBudgetRepository.save(new ProviderBudgetEntity(provider, new BigDecimal("500.00"))));
        budget.resetPeriodIfNeeded(); providerBudgetRepository.save(budget);
        BigDecimal remaining = budget.getMonthlyBudgetCny().subtract(budget.getUsedCny()).max(BigDecimal.ZERO);
        double progress = budget.getMonthlyBudgetCny().signum() == 0 ? 0 : budget.getUsedCny()
                .divide(budget.getMonthlyBudgetCny(), 6, RoundingMode.HALF_UP).doubleValue() * 100;
        ProviderBalanceService.ReportedBalance reported = providerBalanceService.fetch(provider);
        String source = provider == ModelProvider.QWEN ? "OFFICIAL_COST_CENTER_REQUIRED" : reported == null ? "UNAVAILABLE" : reported.source();
        return new ApiDtos.ProviderBudgetView(provider, keyRing.displayName(provider), budget.getMonthlyBudgetCny(), budget.getUsedCny(),
                remaining, Math.min(100, progress), reported == null ? null : reported.amount(), source,
                keyRing.count(provider), "ROUND_ROBIN", keyRing.lastRotation(provider), budget.getUpdatedAt());
    }

    private void requirePending(RechargeOrderEntity order, RechargeStatus expected) {
        if (order.getStatus() == RechargeStatus.PAID) throw new BusinessException(HttpStatus.CONFLICT, "RECHARGE_ALREADY_PAID", "订单已经支付");
        if (order.getStatus() != expected) throw new BusinessException(HttpStatus.CONFLICT, "RECHARGE_NOT_PENDING", "订单当前不可支付");
    }
    private void expireIfNeeded(RechargeOrderEntity order) {
        if (Instant.now().isAfter(order.getExpiresAt())) {
            order.markExpired(); rechargeRepository.save(order);
            throw new BusinessException(HttpStatus.GONE, "RECHARGE_EXPIRED", "订单已过期，请重新创建");
        }
    }
    private void expireForRead(RechargeOrderEntity order) {
        if ((order.getStatus() == RechargeStatus.PENDING_PAYMENT || order.getStatus() == RechargeStatus.PENDING_VERIFICATION)
                && Instant.now().isAfter(order.getExpiresAt())) {
            order.markExpired(); rechargeRepository.save(order);
        }
    }
    private void settle(RechargeOrderEntity order, String description) {
        order.markPaid(); rechargeRepository.save(order); WalletEntity wallet = wallet(order.getUser());
        wallet.credit(order.getAmount()); walletRepository.save(wallet);
        ledgerRepository.save(new WalletLedgerEntity(order.getUser(), "RECHARGE", order.getAmount(), wallet.getBalanceCny(), order.getId(), description));
    }
    private WalletEntity wallet(UserEntity user) { return walletRepository.findByUserId(user.getId()).orElseGet(() -> walletRepository.save(new WalletEntity(user, defaultBalance, defaultQuota))); }
    private ApiDtos.WalletView toWallet(WalletEntity wallet) {
        BigDecimal remaining = wallet.getMonthlyQuotaCny().subtract(wallet.getMonthSpentCny()).max(BigDecimal.ZERO);
        double progress = wallet.getMonthlyQuotaCny().signum() == 0 ? 0 : wallet.getMonthSpentCny().divide(wallet.getMonthlyQuotaCny(), 6, RoundingMode.HALF_UP).doubleValue() * 100;
        return new ApiDtos.WalletView(wallet.getBalanceCny(), wallet.getMonthlyQuotaCny(), wallet.getMonthSpentCny(), remaining,
                Math.min(100, progress), wallet.getQuotaPeriodStart(), wallet.getUpdatedAt());
    }
    private ApiDtos.RechargeOrderView toOrder(RechargeOrderEntity order, String otp, String token) {
        return new ApiDtos.RechargeOrderView(order.getId(), order.getMethod(), order.getAmount(), order.getStatus(), order.getBankLast4(),
                order.getPhoneMasked(), Math.max(0, 5 - order.getVerificationAttempts()), order.getQrPayload(), token,
                order.getCreatedAt(), order.getExpiresAt(), order.getPaidAt(), otp, smsGateway.sandbox());
    }
    private ApiDtos.PublicPaymentView toPublic(RechargeOrderEntity order) {
        return new ApiDtos.PublicPaymentView(order.getId(), order.getMethod(), order.getAmount(), order.getStatus(),
                "Personal Platform", order.getExpiresAt(), order.getPaidAt());
    }
    private ApiDtos.WalletLedgerView toLedger(WalletLedgerEntity ledger) { return new ApiDtos.WalletLedgerView(ledger.getId(), ledger.getType(), ledger.getAmount(), ledger.getBalanceAfter(), ledger.getReferenceId(), ledger.getDescription(), ledger.getCreatedAt()); }
    private String normalizeLast4(String value) { String digits = value == null ? "" : value.replaceAll("\\D", ""); if (!digits.matches("\\d{4}")) throw new BusinessException(HttpStatus.BAD_REQUEST, "BANK_LAST4_INVALID", "银行卡末四位不正确"); return digits; }
    private String maskPhone(String phone) { return phone.substring(0, 3) + "****" + phone.substring(7); }
    private String randomToken() { byte[] bytes = new byte[18]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private record Price(BigDecimal input, BigDecimal output) {}
}
