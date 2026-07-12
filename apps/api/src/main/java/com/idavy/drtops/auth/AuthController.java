package com.idavy.drtops.auth;

import com.idavy.drtops.auth.dto.AuthSessionResponse;
import com.idavy.drtops.auth.dto.ChangePasswordRequest;
import com.idavy.drtops.auth.dto.CurrentUserResponse;
import com.idavy.drtops.auth.dto.LoginRequest;
import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "drt_refresh";

    private final AuthService service;
    private final AuthConfiguration config;

    public AuthController(AuthService service, AuthConfiguration config) {
        this.service = service;
        this.config = config;
    }

    @PostMapping("/login")
    ResponseEntity<ApiResponse<AuthSessionResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = service.login(request.username(), request.password());
        return withRefreshCookie(ResponseEntity.ok(), result).body(ApiResponse.ok(result.session()));
    }

    @PostMapping("/refresh")
    ResponseEntity<ApiResponse<AuthSessionResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        AuthService.LoginResult result = service.refresh(refreshToken);
        return withRefreshCookie(ResponseEntity.ok(), result).body(ApiResponse.ok(result.session()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        service.logout(refreshToken);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString()).build();
    }

    @PostMapping("/password")
    ResponseEntity<Void> changePassword(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody ChangePasswordRequest request) {
        service.changePassword(bearerToken(authorization), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    ApiResponse<CurrentUserResponse> me(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ApiResponse.ok(service.currentUser(bearerToken(authorization)));
    }

    private ResponseEntity.BodyBuilder withRefreshCookie(
            ResponseEntity.BodyBuilder response, AuthService.LoginResult result) {
        return response.header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString());
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(config.isRefreshCookieSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ofDays(config.getRefreshTokenDays()))
                .build();
    }

    private ResponseCookie expiredRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(config.isRefreshCookieSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length());
    }
}
