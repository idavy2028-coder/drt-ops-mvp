package com.idavy.drtops.auth.dto;

import com.idavy.drtops.auth.RoleCode;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

public record UpdateUserRolesRequest(@NotEmpty Set<RoleCode> roles) {
}
