package com.idavy.drtops.auth.dto;

import com.idavy.drtops.auth.RoleCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record CreateUserRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(max = 120) String displayName,
        @NotBlank @Size(min = 8, max = 100) String temporaryPassword,
        @NotEmpty Set<RoleCode> roles) {
}
