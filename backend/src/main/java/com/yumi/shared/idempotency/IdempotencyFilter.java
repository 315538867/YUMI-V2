package com.yumi.shared.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.shared.error.ApiEnvelope;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.web.RequestIdFilter;
import com.yumi.shared.web.WritePolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * 写命令幂等：POST/PUT/PATCH/DELETE（/api/session 之外）必须携带 Idempotency-Key；
 * 同键同请求返回首次业务结果，同键异请求返回 CONFLICT_IDEMPOTENCY；
 * 2xx 与 409 结果落库重放，5xx 不落库允许同键重试。
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "X-Idempotency-Replayed";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String SESSION_PATH = "/api/session";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyRecordStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(IdempotencyRecordStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var uri = request.getRequestURI();
        return !WRITE_METHODS.contains(request.getMethod())
                || !uri.startsWith("/api/")
                || SESSION_PATH.equals(uri)
                || WritePolicy.isReadOnlyPreview(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var requestId = RequestIdFilter.currentId(request);
        var key = request.getHeader(HEADER);
        if (key == null || key.isBlank() || key.length() > 128) {
            writeError(response, requestId, ErrorCode.VALIDATION_INVALID,
                    "写命令必须携带 Idempotency-Key", HEADER);
            return;
        }

        var body = request.getInputStream().readAllBytes();
        var fingerprint = sha256(body);
        var method = request.getMethod();
        var path = request.getRequestURI();
        var admin = currentAdmin();

        var existing = store.findByKey(key);
        if (existing != null) {
            if (matches(existing, admin, method, path, fingerprint)) {
                replay(existing, response);
            } else {
                writeError(response, requestId, ErrorCode.CONFLICT_IDEMPOTENCY,
                        ErrorCode.CONFLICT_IDEMPOTENCY.defaultMessage(), HEADER);
            }
            return;
        }

        var cachedRequest = new CachedBodyRequestWrapper(request, body);
        var cachedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(cachedRequest, cachedResponse);

        var status = cachedResponse.getStatus();
        var storable = status == HttpStatus.CONFLICT.value() || (status >= 200 && status < 300);
        if (storable) {
            try {
                store.insert(new IdempotencyRecordStore.Record(
                        key, admin, method, path, fingerprint, status,
                        cachedResponse.getContentType(), cachedResponse.getContentAsByteArray()));
            } catch (DuplicateKeyException duplicate) {
                log.info("并发同幂等键竞争，保留首条记录 requestId={}", requestId);
            }
        }
        cachedResponse.copyBodyToResponse();
    }

    private boolean matches(IdempotencyRecordStore.Record existing, String admin,
                            String method, String path, String fingerprint) {
        return safeEquals(existing.adminUsername(), admin)
                && existing.httpMethod().equals(method)
                && existing.path().equals(path)
                && existing.requestFingerprint().equals(fingerprint);
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private String currentAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    private void replay(IdempotencyRecordStore.Record record, HttpServletResponse response)
            throws IOException {
        response.setStatus(record.responseStatus());
        if (record.responseContentType() != null) {
            response.setContentType(record.responseContentType());
        }
        response.setHeader(REPLAY_HEADER, "true");
        response.getOutputStream().write(record.responseBody());
        response.getOutputStream().flush();
    }

    private void writeError(HttpServletResponse response, String requestId, ErrorCode errorCode,
                            String message, String field) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiEnvelope.error(errorCode, message, java.util.List.of(new ApiFieldError(field, message)), requestId));
    }

    private String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return stream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return stream.read();
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            var charset = getCharacterEncoding();
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    getInputStream(),
                    charset != null ? java.nio.charset.Charset.forName(charset) : StandardCharsets.UTF_8));
        }
    }
}
