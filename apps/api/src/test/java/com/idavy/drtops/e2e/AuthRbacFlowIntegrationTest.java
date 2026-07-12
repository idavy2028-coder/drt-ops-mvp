package com.idavy.drtops.e2e;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:auth_rbac_flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "drt.auth.jwt-secret=auth-rbac-flow-test-secret-123456789"})
@AutoConfigureMockMvc
class AuthRbacFlowIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach void setUp() { users.deleteAll(); account("admin01", RoleCode.SYSTEM_ADMIN); account("operator01", RoleCode.OPERATOR); account("dispatcher01", RoleCode.DISPATCHER); account("auditor01", RoleCode.AUDITOR); }

    @Test void rolesAuthenticateAndReachOnlyTheirAuthorizedOperations() throws Exception {
        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, bearer(login("admin01")))).andExpect(status().isOk());
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(login("operator01")))).andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/11111111-1111-1111-1111-111111111111/dispatch").header(HttpHeaders.AUTHORIZATION, bearer(login("operator01")))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orders/11111111-1111-1111-1111-111111111111/dispatch").header(HttpHeaders.AUTHORIZATION, bearer(login("dispatcher01")))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/audit-logs").header(HttpHeaders.AUTHORIZATION, bearer(login("auditor01")))).andExpect(status().isOk());
    }

    private void account(String username, RoleCode role) { UserAccount account = UserAccount.create(username, username, passwordEncoder.encode("Secret123!")); account.assignRoles(Set.of(role)); users.save(account); }
    private String login(String username) throws Exception { String body = mockMvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"" + username + "\",\"password\":\"Secret123!\"}")).andReturn().getResponse().getContentAsString(); return com.jayway.jsonpath.JsonPath.read(body, "$.data.accessToken"); }
    private String bearer(String token) { return "Bearer " + token; }
}
