package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class PersonalAiRequestSizeFilterTest {
    @Test void rejectsBothDeclaredAndChunkedOversizedBodiesWithoutEchoingThem() throws Exception {
        var filter = new PersonalAiRequestSizeFilter(new ObjectMapper().findAndRegisterModules());
        for (boolean chunked : new boolean[]{false, true}) {
            var req = new MockHttpServletRequest() {
                @Override public long getContentLengthLong() { return chunked ? -1 : super.getContentLengthLong(); }
            };
            req.setMethod("POST"); req.setRequestURI("/api/v1/personal-ai/preview");
            req.setContent(("sensitive-content" + "x".repeat(PersonalAiRequestSizeFilter.MAX_REQUEST_BYTES)).getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse(); var called = new AtomicBoolean(false);
            filter.doFilter(req, response, (request, res) -> called.set(true));
            assertThat(called).isFalse(); assertThat(response.getStatus()).isEqualTo(413);
            assertThat(response.getContentAsString()).doesNotContain("sensitive-content");
        }
    }
    @Test void preservesValidJsonAndLeavesOtherRoutesUntouched() throws Exception {
        var filter = new PersonalAiRequestSizeFilter(new ObjectMapper().findAndRegisterModules());
        var req = new MockHttpServletRequest("PUT", "/api/v1/personal-ai/settings/OPENAI");
        req.setContent("{\"model\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(req, new MockHttpServletResponse(), (request, response) ->
                assertThat(new String(((HttpServletRequest) request).getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"model\":\"synthetic\"}"));
        var other = new MockHttpServletRequest("POST", "/api/v1/files");
        other.setContent(new byte[PersonalAiRequestSizeFilter.MAX_REQUEST_BYTES + 1]);
        filter.doFilter(other, new MockHttpServletResponse(), (request, response) -> assertThat(request).isSameAs(other));
    }
}
