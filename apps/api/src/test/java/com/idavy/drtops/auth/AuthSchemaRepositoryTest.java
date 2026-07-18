package com.idavy.drtops.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_schema;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuthSchemaRepositoryTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserDetailsService userDetailsService;

    @BeforeEach
    void clearAuthData() {
        refreshTokenRepository.deleteAll();
        userAccountRepository.deleteAll();
    }

    @Test
    void persistsUserRolesAndActiveRefreshToken() {
        UserAccount operator = UserAccount.create("operator01", "运营一组", passwordEncoder.encode("Secret123!"));
        operator.assignRoles(Set.of(RoleCode.OPERATOR));
        userAccountRepository.save(operator);

        RefreshToken token = RefreshToken.issue(operator, "token-hash", OffsetDateTime.now().plusDays(7));
        refreshTokenRepository.save(token);

        assertThat(userAccountRepository.findByUsernameIgnoreCase("OPERATOR01")).isPresent();
        assertThat(refreshTokenRepository.findByTokenHashAndRevokedAtIsNull("token-hash")).isPresent();
    }

    @Test
    void consumesAnActiveRefreshTokenOnlyOnce() {
        UserAccount operator = UserAccount.create("operator02", "operator", passwordEncoder.encode("Secret123!"));
        userAccountRepository.save(operator);
        refreshTokenRepository.save(RefreshToken.issue(operator, "single-use-token", OffsetDateTime.now().plusDays(7)));

        assertThat(refreshTokenRepository.revokeActiveByTokenHash(
                "single-use-token", OffsetDateTime.now(), OffsetDateTime.now())).isEqualTo(1);
        assertThat(refreshTokenRepository.revokeActiveByTokenHash(
                "single-use-token", OffsetDateTime.now(), OffsetDateTime.now())).isZero();
    }

    @Test
    @Transactional
    void locksAnAccountBeforeChangingItsPassword() {
        UserAccount operator = UserAccount.create("operator03", "operator", passwordEncoder.encode("Secret123!"));
        userAccountRepository.saveAndFlush(operator);

        assertThat(userAccountRepository.findByIdForPasswordChange(operator.getId())).isPresent();
    }

    @Test
    void mapsOperatorPermissionsAndLoadsPersistedUserDetails() {
        UserAccount operator = UserAccount.create("operator01", "运营一组", passwordEncoder.encode("Secret123!"));
        operator.assignRoles(Set.of(RoleCode.OPERATOR));
        userAccountRepository.save(operator);

        assertThat(Permission.permissionsFor(Set.of(RoleCode.DISPATCHER)))
                .contains(Permission.ORDER_CREATE);
        assertThat(Permission.permissionsFor(Set.of(RoleCode.OPERATOR)))
                .containsExactlyInAnyOrder(
                        Permission.RESOURCE_MANAGE,
                        Permission.ORDER_CREATE,
                        Permission.ORDER_READ,
                        Permission.TASK_READ,
                        Permission.METRICS_READ)
                .doesNotContain(Permission.TASK_EXECUTE);
        assertThat(Permission.permissionsFor(Set.of(RoleCode.AUDITOR)))
                .containsExactlyInAnyOrder(
                        Permission.AUDIT_READ,
                        Permission.METRICS_READ,
                        Permission.DECISION_READ);

        UserDetails details = userDetailsService.loadUserByUsername("OPERATOR01");
        assertThat(details.getUsername()).isEqualTo("operator01");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .contains("RESOURCE_MANAGE", "ORDER_CREATE", "TASK_READ");
    }
}
