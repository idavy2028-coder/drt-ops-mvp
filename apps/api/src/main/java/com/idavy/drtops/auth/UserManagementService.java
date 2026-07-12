package com.idavy.drtops.auth;

import com.idavy.drtops.auth.dto.CreateUserRequest;
import com.idavy.drtops.auth.dto.UpdateUserRolesRequest;
import com.idavy.drtops.auth.dto.UserAccountResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserManagementService {

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthAuditService authAuditService;

    public UserManagementService(
            UserAccountRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            AuthAuditService authAuditService) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.authAuditService = authAuditService;
    }

    @Transactional(readOnly = true)
    public List<UserAccountResponse> list() {
        return users.findAllByOrderByUsernameAsc().stream().map(UserAccountResponse::from).toList();
    }

    @Transactional
    public UserAccountResponse create(UUID actorId, CreateUserRequest request) {
        if (users.findByUsernameIgnoreCase(request.username()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        UserAccount user = UserAccount.create(
                request.username(), request.displayName(), passwordEncoder.encode(request.temporaryPassword()));
        user.assignRoles(request.roles());
        UserAccount created = users.save(user);
        authAuditService.recordUserChange(actorId, created, "USER_CREATED");
        return UserAccountResponse.from(created);
    }

    @Transactional
    public UserAccountResponse updateRoles(UUID actorId, UUID userId, UpdateUserRolesRequest request) {
        UserAccount user = user(userId);
        user.assignRoles(request.roles());
        revokeSessions(user);
        authAuditService.recordUserChange(actorId, user, "USER_ROLES_UPDATED");
        return UserAccountResponse.from(user);
    }

    @Transactional
    public void resetPassword(UUID actorId, UUID userId, String temporaryPassword) {
        UserAccount user = user(userId);
        user.resetPassword(passwordEncoder.encode(temporaryPassword));
        revokeRefreshTokens(user);
        authAuditService.recordUserChange(actorId, user, "USER_PASSWORD_RESET");
    }

    @Transactional
    public void enable(UUID actorId, UUID userId) {
        UserAccount user = user(userId);
        user.enable();
        authAuditService.recordUserChange(actorId, user, "USER_ENABLED");
    }

    @Transactional
    public void disable(UUID actorId, UUID userId) {
        UserAccount user = user(userId);
        user.disable();
        revokeRefreshTokens(user);
        authAuditService.recordUserChange(actorId, user, "USER_DISABLED");
    }

    private UserAccount user(UUID userId) {
        return users.findByIdForPasswordChange(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private void revokeSessions(UserAccount user) {
        user.revokeAllSessions();
        revokeRefreshTokens(user);
    }

    private void revokeRefreshTokens(UserAccount user) {
        refreshTokens.revokeAllActiveByUserId(user.getId(), OffsetDateTime.now());
    }
}
