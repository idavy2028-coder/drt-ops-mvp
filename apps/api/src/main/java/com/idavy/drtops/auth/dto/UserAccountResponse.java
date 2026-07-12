package com.idavy.drtops.auth.dto;

import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import java.util.Set;
import java.util.UUID;

public record UserAccountResponse(
        UUID id,
        String username,
        String displayName,
        Set<RoleCode> roles,
        boolean enabled,
        boolean mustChangePassword) {

    public static UserAccountResponse from(UserAccount user) {
        return new UserAccountResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRoles(),
                user.isEnabled(),
                user.isMustChangePassword());
    }
}
