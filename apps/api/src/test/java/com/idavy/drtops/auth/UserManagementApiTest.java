package com.idavy.drtops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_management_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "drt.auth.jwt-secret=user-management-api-test-secret-123456"
})
@AutoConfigureMockMvc
class UserManagementApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserAccountRepository users;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtTokenService jwtTokenService;

    private UserAccount admin;
    private UserAccount dispatcher;
    private String adminToken;
    private String dispatcherToken;

    @BeforeEach
    void setUp() {
        refreshTokens.deleteAll();
        auditLogs.deleteAll();
        users.deleteAll();

        admin = account("admin01", RoleCode.SYSTEM_ADMIN);
        dispatcher = account("dispatcher01", RoleCode.DISPATCHER);
        adminToken = jwtTokenService.issue(admin).value();
        dispatcherToken = jwtTokenService.issue(dispatcher).value();
    }

    @Test
    void administratorCreatesDispatcherWithTemporaryPassword() throws Exception {
        mockMvc.perform(post("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "dispatcher02",
                                  "displayName": "Dispatch team two",
                                  "temporaryPassword": "Temp123!",
                                  "roles": ["DISPATCHER"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("dispatcher02"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));

        UserAccount created = users.findByUsernameIgnoreCase("dispatcher02").orElseThrow();
        assertThat(passwordEncoder.matches("Temp123!", created.getPasswordHash())).isTrue();
        assertAuditActions(created.getId(), "USER_CREATED");
    }

    @Test
    void listsUsersOnlyForAdministrators() throws Exception {
        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonAdministratorCannotDisableAnotherUserAndAuditIdentifiesTargetAndActor() throws Exception {
        mockMvc.perform(post("/api/users/{id}/disable", admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken)))
                .andExpect(status().isForbidden());

        var auditLog = auditLogs.findByEntityIdOrderByCreatedAtAsc(admin.getId()).stream()
                .filter(log -> log.getAction().equals("AUTHORIZATION_DENIED"))
                .findFirst()
                .orElseThrow();
        assertThat(auditLog.getActorId()).isEqualTo(dispatcher.getId().toString());
    }

    @Test
    void rolesPasswordResetAndDisableAreAuditedAndRevokeSessions() throws Exception {
        String originalToken = jwtTokenService.issue(dispatcher).value();
        String resetRefresh = "reset-refresh-token";
        refreshTokens.saveAndFlush(RefreshToken.issue(
                dispatcher, sha256(resetRefresh), OffsetDateTime.now().plusDays(1)));

        mockMvc.perform(put("/api/users/{id}/roles", dispatcher.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"OPERATOR\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("OPERATOR"));

        UserAccount afterRoleChange = users.findById(dispatcher.getId()).orElseThrow();
        assertThat(afterRoleChange.getTokenVersion()).isEqualTo(1);
        assertThat(refreshTokens.findByTokenHashAndRevokedAtIsNull(sha256(resetRefresh))).isEmpty();
        assertOldAccessTokenIsRejected(originalToken);

        String passwordRefresh = "password-refresh-token";
        refreshTokens.saveAndFlush(RefreshToken.issue(
                afterRoleChange, sha256(passwordRefresh), OffsetDateTime.now().plusDays(1)));
        mockMvc.perform(post("/api/users/{id}/reset-password", dispatcher.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"Reset123!\"}"))
                .andExpect(status().isNoContent());

        UserAccount afterReset = users.findById(dispatcher.getId()).orElseThrow();
        assertThat(afterReset.getTokenVersion()).isEqualTo(2);
        assertThat(afterReset.isMustChangePassword()).isTrue();
        assertThat(passwordEncoder.matches("Reset123!", afterReset.getPasswordHash())).isTrue();
        assertThat(refreshTokens.findByTokenHashAndRevokedAtIsNull(sha256(passwordRefresh))).isEmpty();

        String disableRefresh = "disable-refresh-token";
        refreshTokens.saveAndFlush(RefreshToken.issue(
                afterReset, sha256(disableRefresh), OffsetDateTime.now().plusDays(1)));
        mockMvc.perform(post("/api/users/{id}/disable", dispatcher.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNoContent());

        UserAccount disabled = users.findById(dispatcher.getId()).orElseThrow();
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.getTokenVersion()).isEqualTo(3);
        assertThat(refreshTokens.findByTokenHashAndRevokedAtIsNull(sha256(disableRefresh))).isEmpty();
        assertAuditActions(dispatcher.getId(), "USER_ROLES_UPDATED", "USER_PASSWORD_RESET", "USER_DISABLED");
    }

    @Test
    void enablesAUserAndWritesAuditLog() throws Exception {
        dispatcher.disable();
        users.saveAndFlush(dispatcher);

        mockMvc.perform(post("/api/users/{id}/enable", dispatcher.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNoContent());

        assertThat(users.findById(dispatcher.getId()).orElseThrow().isEnabled()).isTrue();
        assertAuditActions(dispatcher.getId(), "USER_ENABLED");
    }

    @Test
    void recordsKnownUserLoginFailureAndRefreshReplayWithoutSecretMaterial() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dispatcher01\",\"password\":\"WrongPassword!\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dispatcher01\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        var refreshCookie = login.getResponse().getCookie("drt_refresh");
        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized());

        assertAuditActions(dispatcher.getId(), "AUTH_LOGIN_FAILED", "AUTH_REFRESH_REPLAY");
        auditLogs.findByEntityIdOrderByCreatedAtAsc(dispatcher.getId()).forEach(log -> {
            assertThat(log.getMetadataJson()).doesNotContain("WrongPassword!", "Secret123!", refreshCookie.getValue());
        });
    }

    @Test
    void recordsUnknownUserLoginFailureWithoutSecretMaterial() throws Exception {
        String password = "UnknownUserPassword!";
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"missing-user\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized());

        var auditLog = auditLogs.findAll().stream()
                .filter(log -> log.getAction().equals("AUTH_LOGIN_FAILED"))
                .findFirst()
                .orElseThrow();
        assertThat(auditLog.getReason()).doesNotContain(password);
        assertThat(auditLog.getMetadataJson()).doesNotContain(password);
    }

    private UserAccount account(String username, RoleCode role) {
        UserAccount account = UserAccount.create(username, username, passwordEncoder.encode("Secret123!"));
        account.assignRoles(Set.of(role));
        return users.saveAndFlush(account);
    }

    private void assertOldAccessTokenIsRejected(String accessToken) throws Exception {
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isUnauthorized());
    }

    private void assertAuditActions(UUID userId, String... actions) {
        List<String> actualActions = auditLogs.findByEntityIdOrderByCreatedAtAsc(userId).stream()
                .map(log -> log.getAction())
                .toList();
        assertThat(actualActions).contains(actions);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
