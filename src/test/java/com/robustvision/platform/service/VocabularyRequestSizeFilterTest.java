package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class VocabularyRequestSizeFilterTest {
    @Test void rejectsDeclaredAndChunkedOversizedImportBeforeParsingWithoutEchoingContent() throws Exception {
        for(boolean chunked:new boolean[]{false,true}) {
            var request=new MockHttpServletRequest("POST","/api/v1/vocabulary/books/import") {
                @Override public long getContentLengthLong(){return chunked?-1:super.getContentLengthLong();}
            };
            request.setContent(("PRIVATE_WORD_CONTENT"+"x".repeat(VocabularyRequestSizeFilter.MAX_IMPORT_BYTES)).getBytes(StandardCharsets.UTF_8));
            var response=new MockHttpServletResponse();var called=new AtomicBoolean();
            new VocabularyRequestSizeFilter(new ObjectMapper().findAndRegisterModules()).doFilter(request,response,(req,res)->called.set(true));
            assertThat(called.get()).isFalse();assertThat(response.getStatus()).isEqualTo(413);assertThat(response.getContentAsString()).doesNotContain("PRIVATE_WORD_CONTENT");
        }
    }
    @Test void boundsOrdinaryWritesAndUsesServletPathUnderContextPrefix() throws Exception {
        for(String path:new String[]{"/api/v1/vocabulary/next","/api/v1/vocabulary/settings","/api/v1/vocabulary/questions/q/answer"}) {
            var request=new MockHttpServletRequest("POST","/context"+path);request.setServletPath(path);request.setContent(new byte[VocabularyRequestSizeFilter.MAX_WRITE_BYTES+1]);
            var called=new AtomicBoolean();var response=new MockHttpServletResponse();
            new VocabularyRequestSizeFilter(new ObjectMapper().findAndRegisterModules()).doFilter(request,response,(req,res)->called.set(true));
            assertThat(called.get()).isFalse();assertThat(response.getStatus()).isEqualTo(413);
        }
    }
    @Test void validBodiesRoundTripExactlyAndOtherRoutesRemainUntouched() throws Exception {
        var filter=new VocabularyRequestSizeFilter(new ObjectMapper().findAndRegisterModules());
        var request=new MockHttpServletRequest("POST","/api/v1/vocabulary/books/import");String json="{\"title\":\"中文词书\",\"words\":[]}";request.setContent(json.getBytes(StandardCharsets.UTF_8));
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->assertThat(new String(req.getInputStream().readAllBytes(),StandardCharsets.UTF_8)).isEqualTo(json));
        var other=new MockHttpServletRequest("POST","/api/v1/files");other.setContent(new byte[VocabularyRequestSizeFilter.MAX_IMPORT_BYTES+1]);
        filter.doFilter(other,new MockHttpServletResponse(),(req,res)->assertThat(req).isSameAs(other));
    }
}
