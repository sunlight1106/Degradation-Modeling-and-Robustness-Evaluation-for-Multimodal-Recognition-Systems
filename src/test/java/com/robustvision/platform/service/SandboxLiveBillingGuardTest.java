package com.robustvision.platform.service;
import com.robustvision.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
class SandboxLiveBillingGuardTest {
    @Test void liveAndHttpModesRejectEverySandboxSettlementEntryBeforeReadingOrders() {
        BillingService service = mock(BillingService.class, CALLS_REAL_METHODS);
        for (String mode : new String[]{"live", "http"}) {
            ReflectionTestUtils.setField(service, "modelMode", mode);
            assertThatThrownBy(() -> service.createRecharge(null)).isInstanceOf(BusinessException.class).hasMessageContaining("禁止沙箱充值");
            assertThatThrownBy(() -> service.confirmRecharge("synthetic", null)).isInstanceOf(BusinessException.class).hasMessageContaining("禁止沙箱充值");
            assertThatThrownBy(() -> service.completeQrPayment("synthetic")).isInstanceOf(BusinessException.class).hasMessageContaining("禁止沙箱充值");
        }
    }
}
