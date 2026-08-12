package com.idavy.drtops.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class GatewayServiceAuthenticationFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL = "JT_GATEWAY_SERVICE";
    private static final String INTERNAL_PREFIX = "/internal/jt-gateway/";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String VERSION_HEADER = "X-Service-Credential-Version";

    private final Credential current;
    private final Credential previous;

    public GatewayServiceAuthenticationFilter(
            @Value("${jt.gateway.service-credentials.current.version:}") String currentVersion,
            @Value("${jt.gateway.service-credentials.current.hash:}") String currentHash,
            @Value("${jt.gateway.service-credentials.previous.version:}") String previousVersion,
            @Value("${jt.gateway.service-credentials.previous.hash:}") String previousHash) {
        this.current = Credential.configured(currentVersion, currentHash);
        this.previous = Credential.configured(previousVersion, previousHash);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String version = request.getHeader(VERSION_HEADER);
        if (headerCount(request, "Authorization") != 1
                || headerCount(request, VERSION_HEADER) != 1
                || !StringUtils.hasText(authorization)
                || !authorization.startsWith(BEARER_PREFIX)
                || authorization.length() == BEARER_PREFIX.length()
                || !matches(version, authorization.substring(BEARER_PREFIX.length()))) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        SecurityContext previousContext = SecurityContextHolder.getContext();
        SecurityContext gatewayContext = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                PRINCIPAL, null, List.of(new SimpleGrantedAuthority(PRINCIPAL)));
        gatewayContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(gatewayContext);
        try {
            filterChain.doFilter(new AuthorizationMaskingRequest(request), response);
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }

    private static int headerCount(HttpServletRequest request, String name) {
        return Collections.list(request.getHeaders(name)).size();
    }

    private boolean matches(String version, String credential) {
        Credential selected = current.hasVersion(version) ? current
                : previous.hasVersion(version) ? previous : Credential.UNCONFIGURED;
        if (!selected.configured()) {
            return false;
        }
        byte[] presented = sha256(credential);
        try {
            return MessageDigest.isEqual(selected.digest(), presented);
        } finally {
            java.util.Arrays.fill(presented, (byte) 0);
        }
    }

    private static byte[] sha256(String credential) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Credential(String version, byte[] digest) {
        private static final Credential UNCONFIGURED = new Credential("", new byte[0]);

        static Credential configured(String version, String hash) {
            if (!StringUtils.hasText(version) || hash == null || !hash.matches("[0-9a-f]{64}")) {
                return UNCONFIGURED;
            }
            return new Credential(version, HexFormat.of().parseHex(hash));
        }

        boolean configured() {
            return digest.length == 32;
        }

        boolean hasVersion(String candidate) {
            return configured() && version.equals(candidate);
        }
    }

    private static final class AuthorizationMaskingRequest extends HttpServletRequestWrapper {
        private AuthorizationMaskingRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }
    }
}
