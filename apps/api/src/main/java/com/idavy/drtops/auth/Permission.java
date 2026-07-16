package com.idavy.drtops.auth;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum Permission {
    USER_MANAGE,
    RULE_MANAGE,
    RESOURCE_MANAGE,
    ORDER_CREATE,
    ORDER_READ,
    DISPATCH_EXECUTE,
    MANUAL_REVIEW,
    TASK_READ,
    TASK_EXECUTE,
    AUDIT_READ,
    METRICS_READ,
    DECISION_READ,
    LOCATION_READ,
    LOCATION_REPORT,
    LOCATION_CORRECT,
    LOCATION_EXPORT;

    private static final Map<RoleCode, Set<Permission>> ROLE_PERMISSIONS = Map.of(
            RoleCode.SYSTEM_ADMIN, Set.of(
                    USER_MANAGE,
                    RULE_MANAGE,
                    RESOURCE_MANAGE,
                    ORDER_READ,
                    TASK_READ,
                    AUDIT_READ,
                    METRICS_READ,
                    DECISION_READ,
                    LOCATION_READ,
                    LOCATION_REPORT,
                    LOCATION_CORRECT,
                    LOCATION_EXPORT),
            RoleCode.DISPATCHER, Set.of(
                    ORDER_READ,
                    DISPATCH_EXECUTE,
                    MANUAL_REVIEW,
                    TASK_READ,
                    TASK_EXECUTE,
                    DECISION_READ,
                    LOCATION_READ,
                    LOCATION_REPORT),
            RoleCode.OPERATOR, Set.of(
                    RESOURCE_MANAGE,
                    ORDER_CREATE,
                    ORDER_READ,
                    TASK_READ,
                    METRICS_READ),
            RoleCode.AUDITOR, Set.of(
                    AUDIT_READ,
                    METRICS_READ,
                    DECISION_READ));

    public static Set<Permission> permissionsFor(Set<RoleCode> roles) {
        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (RoleCode role : roles) {
            permissions.addAll(ROLE_PERMISSIONS.getOrDefault(role, Set.of()));
        }
        return Set.copyOf(permissions);
    }
}
