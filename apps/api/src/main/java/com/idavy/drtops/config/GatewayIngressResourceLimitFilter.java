package com.idavy.drtops.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces a bounded raw JSON budget only for the gateway ingress batch endpoint. */
@Component
public class GatewayIngressResourceLimitFilter extends OncePerRequestFilter {

    private static final String INGRESS_PATH = "/internal/jt-gateway/ingress";
    private static final int COPY_BUFFER_BYTES = 8 * 1024;

    private final int maximumRequestBytes;
    private final int maximumStringLength;
    private final JsonFactory constraintCheckingFactory;

    public GatewayIngressResourceLimitFilter(
            @Value("${jt.gateway.ingress.max-request-bytes:1048576}") int maximumRequestBytes,
            @Value("${jt.gateway.ingress.max-json-nesting-depth:32}") int maximumNestingDepth,
            @Value("${jt.gateway.ingress.max-json-string-length:262144}") int maximumStringLength) {
        if (maximumRequestBytes <= 0 || maximumNestingDepth <= 0 || maximumStringLength <= 0) {
            throw new IllegalArgumentException("gateway ingress resource limits must be positive");
        }
        this.maximumRequestBytes = maximumRequestBytes;
        this.maximumStringLength = maximumStringLength;
        this.constraintCheckingFactory = new JsonFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxDocumentLength(maximumRequestBytes)
                        .maxNestingDepth(maximumNestingDepth)
                        .maxStringLength(maximumStringLength)
                        .build());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !INGRESS_PATH.equals(pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maximumRequestBytes) {
            rejectTooLarge(response);
            return;
        }

        byte[] body;
        try {
            body = readWithinLimit(request);
        } catch (RequestBodyTooLargeException tooLarge) {
            rejectTooLarge(response);
            return;
        }

        try {
            validateJsonConstraints(body);
        } catch (StreamConstraintsException constrained) {
            rejectTooLarge(response);
            return;
        } catch (JsonProcessingException malformed) {
            // Preserve the controller/Jackson 400 contract for malformed JSON that is within the resource budget.
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private byte[] readWithinLimit(HttpServletRequest request) throws IOException {
        ByteArrayOutputStream copy = new ByteArrayOutputStream(
                Math.min(maximumRequestBytes, COPY_BUFFER_BYTES));
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        ServletInputStream input = request.getInputStream();
        int copied = 0;
        while (true) {
            int remaining = maximumRequestBytes - copied;
            int readLength = remaining >= buffer.length ? buffer.length : remaining + 1;
            int read = input.read(buffer, 0, readLength);
            if (read < 0) {
                return copy.toByteArray();
            }
            if (read > remaining) {
                throw new RequestBodyTooLargeException();
            }
            copy.write(buffer, 0, read);
            copied += read;
        }
    }

    private void validateJsonConstraints(byte[] body) throws IOException {
        try (JsonParser parser = constraintCheckingFactory.createParser(body)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                parser.finishToken();
                if ((token == JsonToken.VALUE_STRING || token == JsonToken.FIELD_NAME)
                        && parser.getTextLength() > maximumStringLength) {
                    throw new StreamConstraintsException(
                            "gateway ingress JSON string exceeds configured limit");
                }
            }
        }
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty()
                ? requestUri : requestUri.substring(contextPath.length());
    }

    private static void rejectTooLarge(HttpServletResponse response) {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }

    private static final class RequestBodyTooLargeException extends IOException {
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) { }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] target, int offset, int length) {
                    return input.read(target, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
