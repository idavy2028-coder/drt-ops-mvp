package com.idavy.drtops.auth;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import jakarta.servlet.http.Cookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Set;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "drt.auth.jwt-secret=01234567890123456789012345678901"
})
@AutoConfigureMockMvc
class AuthApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired UserAccountRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenService jwtTokenService;
    @BeforeEach void setUp(){ users.deleteAll(); UserAccount user=UserAccount.create("operator01","运营一组",passwordEncoder.encode("Secret123!")); user.assignRoles(Set.of(RoleCode.OPERATOR)); users.save(user); }

    @AfterEach
    void tearDown() {
        refreshTokens.deleteAll();
    }

    @Test
    void logsInWithLocalAccountAndIssuesRefreshCookie() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operator01\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(header().string("Set-Cookie", containsString("drt_refresh=")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andReturn();

        Cookie refreshCookie = login.getResponse().getCookie("drt_refresh");
        assertThat(refreshCookie.isHttpOnly()).isTrue();
        assertThat(refreshCookie.getSecure()).isTrue();
        assertThat(refreshCookie.getPath()).isEqualTo("/api/auth");
    }

    @Test
    void rejectsIncorrectPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operator01\",\"password\":\"WrongSecret123!\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rotatesRefreshTokenAndRejectsThePreviousToken() throws Exception {
        Cookie previousToken = loginAndReadRefreshCookie();

        MvcResult refresh = mockMvc.perform(post("/api/auth/refresh").cookie(previousToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(header().string("Set-Cookie", containsString("drt_refresh=")))
                .andReturn();

        mockMvc.perform(post("/api/auth/refresh").cookie(previousToken))
                .andExpect(status().isUnauthorized());

        Cookie rotatedToken = refresh.getResponse().getCookie("drt_refresh");
        mockMvc.perform(post("/api/auth/refresh").cookie(rotatedToken))
                .andExpect(status().isOk());
    }

    @Test
    void revokesRefreshTokenOnLogout() throws Exception {
        Cookie refreshToken = loginAndReadRefreshCookie();

        mockMvc.perform(post("/api/auth/logout").cookie(refreshToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh").cookie(refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readsCurrentUserFromAccessToken() throws Exception {
        UserAccount user = users.findByUsernameIgnoreCase("operator01").orElseThrow();
        String accessToken = jwtTokenService.issue(user).value();

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("operator01"));
    }

    @Test
    void rejectsAccessTokenAfterAccountIsDisabled() throws Exception {
        UserAccount user = users.findByUsernameIgnoreCase("operator01").orElseThrow();
        String accessToken = jwtTokenService.issue(user).value();
        user.disable();
        users.save(user);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changesInitialPasswordAndRevokesExistingSessions() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operator01\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = JsonPath.read(login.getResponse().getContentAsString(), "$.data.accessToken");

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));

        mockMvc.perform(post("/api/auth/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Secret123!\",\"newPassword\":\"ChangedSecret123!\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operator01\",\"password\":\"ChangedSecret123!\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsRefreshTokenIssuedBeforePasswordChangeVersion() throws Exception {
        UserAccount user = users.findByUsernameIgnoreCase("operator01").orElseThrow();
        user.changePassword(passwordEncoder.encode("ChangedSecret123!"));
        users.saveAndFlush(user);
        String rawRefreshToken = "refresh-token-created-during-password-change";
        refreshTokens.saveAndFlush(RefreshToken.issue(
                user,
                sha256(rawRefreshToken),
                java.time.OffsetDateTime.now().plusDays(7),
                user.getTokenVersion() - 1));

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("drt_refresh", rawRefreshToken)))
                .andExpect(status().isUnauthorized());
    }

    private String sha256(String value) throws java.security.NoSuchAlgorithmException {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private Cookie loginAndReadRefreshCookie() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operator01\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return login.getResponse().getCookie("drt_refresh");
    }
}
