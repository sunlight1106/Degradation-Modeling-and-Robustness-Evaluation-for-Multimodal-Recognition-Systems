package com.robustvision.platform.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import static org.assertj.core.api.Assertions.assertThat;

class SensitiveRequestLoggingTest {
    @Test void neverLogsBearerPathTokenOrExceptionBody() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest("GET", "/api/v1/notes/shared/SYNTHETIC_SECRET_TOKEN");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/notes/shared/{token}");
            var handler = new GlobalExceptionHandler();
            handler.handleBusiness(new BusinessException(HttpStatus.GONE, "SHARE_EXPIRED", "SYNTHETIC_SECRET_ERROR"), request);
            handler.handleUnexpected(new IllegalStateException("SYNTHETIC_SECRET_JSON"), request);
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("SYNTHETIC_SECRET").contains("{token}");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); }
    }
}
