package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Bound vocabulary JSON before Jackson materializes any user-controlled collection. */
@Component @Order(21)
public class VocabularyRequestSizeFilter extends OncePerRequestFilter {
    static final int MAX_IMPORT_BYTES = 2 * 1024 * 1024;
    static final int MAX_WRITE_BYTES = 16384;
    private final ObjectMapper mapper;
    public VocabularyRequestSizeFilter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
        return !path.startsWith("/api/v1/vocabulary/")
                || !Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
        int max = path.equals("/api/v1/vocabulary/books/import") ? MAX_IMPORT_BYTES : MAX_WRITE_BYTES;
        if (request.getContentLengthLong() > max) { reject(response); return; }
        byte[] bytes = request.getInputStream().readNBytes(max + 1);
        if (bytes.length > max) { reject(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return bytes.length; }
            @Override public long getContentLengthLong() { return bytes.length; }
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous request body"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ApiResponse.failure("REQUEST_TOO_LARGE", "请求内容过长，请减少内容后重试"));
    }
}
