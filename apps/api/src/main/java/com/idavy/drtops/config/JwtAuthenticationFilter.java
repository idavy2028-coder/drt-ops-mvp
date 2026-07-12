package com.idavy.drtops.config;

import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.Permission;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import java.io.IOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final UserAccountRepository userAccountRepository;
    private final AuthenticationFailureHandler authenticationFailureHandler;

    public JwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            UserAccountRepository userAccountRepository,
            AuthenticationFailureHandler authenticationFailureHandler) {
        this.jwtTokenService = jwtTokenService;
        this.userAccountRepository = userAccountRepository;
        this.authenticationFailureHandler = authenticationFailureHandler;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith(BEARER_PREFIX) || authorization.length() == BEARER_PREFIX.length()) {
            authenticationFailureHandler.commence(request, response, null);
            return;
        }

        try {
            Jwt jwt = jwtTokenService.decode(authorization.substring(BEARER_PREFIX.length()));
            UserAccount account = userAccountRepository.findById(UUID.fromString(jwt.getSubject()))
                    .orElseThrow(() -> new JwtException("Token subject does not exist"));
            if (!account.isEnabled() || account.getTokenVersion() != tokenVersion(jwt)) {
                throw new JwtException("Token is no longer valid");
            }

            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    account.getId(),
                    null,
                    authorities(jwt));
            ((UsernamePasswordAuthenticationToken) authentication)
                    .setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        } catch (JwtException | IllegalArgumentException exception) {
            SecurityContextHolder.clearContext();
            authenticationFailureHandler.commence(request, response, null);
        }
    }

    private long tokenVersion(Jwt jwt) {
        Object claim = jwt.getClaims().get("tokenVersion");
        if (!(claim instanceof Number tokenVersion)) {
            throw new JwtException("Token version is missing");
        }
        return tokenVersion.longValue();
    }

    private Collection<SimpleGrantedAuthority> authorities(Jwt jwt) {
        List<String> roleNames = jwt.getClaimAsStringList("roles");
        if (roleNames == null) {
            throw new JwtException("Token roles are missing");
        }
        Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
        for (String roleName : roleNames) {
            roles.add(RoleCode.valueOf(roleName));
        }
        return Permission.permissionsFor(roles).stream()
                .map(Permission::name)
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
