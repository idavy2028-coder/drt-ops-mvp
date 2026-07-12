package com.idavy.drtops.auth;

import com.idavy.drtops.auth.dto.CreateUserRequest;
import com.idavy.drtops.auth.dto.ResetPasswordRequest;
import com.idavy.drtops.auth.dto.UpdateUserRolesRequest;
import com.idavy.drtops.auth.dto.UserAccountResponse;
import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserManagementController {

    private final UserManagementService service;

    public UserManagementController(UserManagementService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<UserAccountResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    ResponseEntity<ApiResponse<UserAccountResponse>> create(
            Authentication authentication, @Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(actorId(authentication), request)));
    }

    @PutMapping("/{userId}/roles")
    ApiResponse<UserAccountResponse> updateRoles(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRolesRequest request) {
        return ApiResponse.ok(service.updateRoles(actorId(authentication), userId, request));
    }

    @PostMapping("/{userId}/reset-password")
    ResponseEntity<Void> resetPassword(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(actorId(authentication), userId, request.temporaryPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/enable")
    ResponseEntity<Void> enable(Authentication authentication, @PathVariable UUID userId) {
        service.enable(actorId(authentication), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/disable")
    ResponseEntity<Void> disable(Authentication authentication, @PathVariable UUID userId) {
        service.disable(actorId(authentication), userId);
        return ResponseEntity.noContent().build();
    }

    private UUID actorId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }
}
