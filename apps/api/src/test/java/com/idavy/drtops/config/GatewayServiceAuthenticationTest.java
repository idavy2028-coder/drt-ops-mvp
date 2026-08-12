package com.idavy.drtops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.auth.Permission;
import com.idavy.drtops.auth.RoleCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gateway_service_auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Import(GatewayServiceAuthenticationTest.ProbeConfiguration.class)
class GatewayServiceAuthenticationTest {

    private static final String CURRENT = java.util.UUID.randomUUID().toString();
    private static final String PREVIOUS = java.util.UUID.randomUUID().toString();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext applicationContext;

    @DynamicPropertySource
    static void gatewayCredentials(DynamicPropertyRegistry registry) {
        registry.add("jt.gateway.service-credentials.current.version", () -> "2");
        registry.add("jt.gateway.service-credentials.current.hash", () -> sha256(CURRENT));
        registry.add("jt.gateway.service-credentials.previous.version", () -> "1");
        registry.add("jt.gateway.service-credentials.previous.hash", () -> sha256(PREVIOUS));
    }

    @Test
    void grantsTerminalPermissionsOnlyToSystemAdmin() {
        assertThat(Permission.permissionsFor(Set.of(RoleCode.SYSTEM_ADMIN)))
                .contains(Permission.TERMINAL_READ, Permission.TERMINAL_MANAGE);
        assertThat(Permission.permissionsFor(Set.of(RoleCode.DISPATCHER)))
                .doesNotContain(Permission.TERMINAL_READ, Permission.TERMINAL_MANAGE);
        assertThat(Permission.permissionsFor(Set.of(RoleCode.OPERATOR)))
                .doesNotContain(Permission.TERMINAL_READ, Permission.TERMINAL_MANAGE);
        assertThat(Permission.permissionsFor(Set.of(RoleCode.AUDITOR)))
                .doesNotContain(Permission.TERMINAL_READ, Permission.TERMINAL_MANAGE);
    }

    @Test
    void acceptsCurrentAndPreviousServiceCredentialVersions() throws Exception {
        performInternal("2", CURRENT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value("JT_GATEWAY_SERVICE"));
        performInternal("1", PREVIOUS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value("JT_GATEWAY_SERVICE"));
    }

    @Test
    void rejectsMissingVersionMismatchAndWrongCredentialWithoutEchoingSecrets() throws Exception {
        mockMvc.perform(get("/internal/jt-gateway/probe"))
                .andExpect(status().isUnauthorized());
        performInternal("1", CURRENT)
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(CURRENT))))
                .andExpect(content().string(not(containsString(sha256(CURRENT)))));
        String wrongCredential = java.util.UUID.randomUUID().toString();
        performInternal("2", wrongCredential)
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(wrongCredential))));
        performInternal("999", CURRENT)
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "TERMINAL_READ")
    void browserAuthenticationCannotEnterGatewayInternalDomain() throws Exception {
        mockMvc.perform(get("/internal/jt-gateway/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceCredentialCannotEnterOperatorApiDomain() throws Exception {
        mockMvc.perform(get("/api/test-operator-domain")
                        .header("Authorization", "Bearer " + CURRENT)
                        .header("X-Service-Credential-Version", "2"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDuplicateAuthorizationOrCredentialVersionHeaders() throws Exception {
        mockMvc.perform(get("/internal/jt-gateway/probe")
                        .header("Authorization", "Bearer " + CURRENT, "Bearer " + CURRENT)
                        .header("X-Service-Credential-Version", "2"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/jt-gateway/probe")
                        .header("Authorization", "Bearer " + CURRENT)
                        .header("X-Service-Credential-Version", "2", "2"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void masksAuthorizationThroughBothHeaderAccessorsAndDisablesContainerRegistration() throws Exception {
        performInternal("2", CURRENT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationHeaderMasked").value(true))
                .andExpect(jsonPath("$.authorizationHeadersMasked").value(true))
                .andExpect(jsonPath("$.versionHeaderMasked").value(true))
                .andExpect(jsonPath("$.versionHeadersMasked").value(true));

        FilterRegistrationBean<?> registration = applicationContext.getBean(
                "gatewayServiceAuthenticationFilterRegistration", FilterRegistrationBean.class);
        assertThat(registration.isEnabled()).isFalse();
    }

    private org.springframework.test.web.servlet.ResultActions performInternal(String version, String credential)
            throws Exception {
        return mockMvc.perform(get("/internal/jt-gateway/probe")
                .header("Authorization", "Bearer " + credential)
                .header("X-Service-Credential-Version", version));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/internal/jt-gateway/probe")
        java.util.Map<String, Object> internal(Authentication authentication, HttpServletRequest request) {
            return java.util.Map.of(
                    "principal", authentication.getName(),
                    "authorizationHeaderMasked", request.getHeader("Authorization") == null,
                    "authorizationHeadersMasked", !request.getHeaders("Authorization").hasMoreElements(),
                    "versionHeaderMasked", request.getHeader("X-Service-Credential-Version") == null,
                    "versionHeadersMasked",
                    !request.getHeaders("X-Service-Credential-Version").hasMoreElements());
        }

        @GetMapping("/api/test-operator-domain")
        java.util.Map<String, String> operator() {
            return java.util.Map.of("status", "ok");
        }
    }
}
