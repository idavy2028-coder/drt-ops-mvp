package com.idavy.drtops.auth;

import com.idavy.drtops.auth.dto.AuthSessionResponse;
import com.idavy.drtops.auth.dto.CurrentUserResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserAccountRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwt;
    private final AuthConfiguration config;
    private final AuthAuditService authAuditService;

    public AuthService(
            UserAccountRepository users,
            RefreshTokenRepository tokens,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwt,
            AuthConfiguration config,
            AuthAuditService authAuditService) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.config = config;
        this.authAuditService = authAuditService;
    }

    @Transactional
    public LoginResult login(String username, String password) {
        UserAccount user = users.findByUsernameIgnoreCase(username).orElseThrow(this::invalid);
        if (!user.isEnabled() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            authAuditService.recordAuthenticationFailure(user, "AUTH_LOGIN_FAILED");
            throw invalid();
        }
        return issueSession(user);
    }

    @Transactional
    public LoginResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw invalid();
        }
        String tokenHash = hash(rawRefreshToken);
        RefreshToken current = tokens.findByTokenHash(tokenHash)
                .orElseThrow(this::invalid);
        UserAccount user = current.getUser();
        if (current.getRevokedAt() != null) {
            authAuditService.recordAuthenticationFailure(user, "AUTH_REFRESH_REPLAY");
            throw invalid();
        }
        if (!user.isEnabled() || current.getTokenVersion() != user.getTokenVersion()) {
            authAuditService.recordAuthenticationFailure(user, "AUTH_REFRESH_REJECTED");
            throw invalid();
        }
        if (tokens.revokeActiveByTokenHash(tokenHash, OffsetDateTime.now(), OffsetDateTime.now()) != 1) {
            authAuditService.recordAuthenticationFailure(user, "AUTH_REFRESH_REPLAY");
            throw invalid();
        }
        return issueSession(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        tokens.revokeActiveByTokenHash(hash(rawRefreshToken), OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(String accessToken) {
        return currentUser(authenticatedUser(accessToken));
    }

    @Transactional
    public void changePassword(String accessToken, String currentPassword, String newPassword) {
        UserAccount user = authenticatedUserForPasswordChange(accessToken);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw invalid();
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        tokens.revokeAllActiveByUserId(user.getId(), OffsetDateTime.now());
    }

    private UserAccount authenticatedUser(String accessToken) {
        return authenticatedUser(accessToken, users::findById);
    }

    private UserAccount authenticatedUserForPasswordChange(String accessToken) {
        return authenticatedUser(accessToken, users::findByIdForPasswordChange);
    }

    private UserAccount authenticatedUser(
            String accessToken, Function<UUID, Optional<UserAccount>> accountLookup) {
        try {
            Jwt decoded = jwt.decode(accessToken);
            UUID userId = UUID.fromString(decoded.getSubject());
            Number tokenVersion = decoded.getClaim("tokenVersion");
            return accountLookup.apply(userId)
                    .filter(UserAccount::isEnabled)
                    .filter(account -> tokenVersion != null && account.getTokenVersion() == tokenVersion.longValue())
                    .orElseThrow(this::invalid);
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private LoginResult issueSession(UserAccount user) {
        JwtTokenService.IssuedToken access = jwt.issue(user);
        String refresh = randomToken();
        tokens.save(RefreshToken.issue(
                user,
                hash(refresh),
                OffsetDateTime.now().plusDays(config.getRefreshTokenDays())));
        return new LoginResult(session(user, access), refresh);
    }

    private AuthSessionResponse session(UserAccount user, JwtTokenService.IssuedToken access) {
        return new AuthSessionResponse(access.value(), access.expiresAt(), currentUser(user));
    }

    private CurrentUserResponse currentUser(UserAccount user) {
        return new CurrentUserResponse(
                user.getId().toString(),
                user.getUsername(),
                user.getRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                user.isMustChangePassword());
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "认证信息无效");
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record LoginResult(AuthSessionResponse session, String refreshToken) {
    }
}
