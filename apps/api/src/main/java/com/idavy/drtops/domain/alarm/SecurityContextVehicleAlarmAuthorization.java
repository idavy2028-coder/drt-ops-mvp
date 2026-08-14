package com.idavy.drtops.domain.alarm;

import com.idavy.drtops.auth.Permission;
import com.idavy.drtops.auth.UserAccountRepository;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Rechecks the authenticated principal and authority at the domain boundary. */
@Component
class SecurityContextVehicleAlarmAuthorization implements VehicleAlarmAuthorization {
    private final UserAccountRepository users;

    SecurityContextVehicleAlarmAuthorization(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    public boolean mayRead(UUID actorId) {
        return has(actorId, Permission.VEHICLE_ALARM_READ);
    }

    @Override
    public boolean mayContinueRead(UUID actorId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            return authentication.isAuthenticated()
                    && actorId.equals(currentActor(authentication))
                    && authentication.getAuthorities().stream()
                    .anyMatch(granted -> Permission.VEHICLE_ALARM_READ.name().equals(granted.getAuthority()));
        }
        return users.findWithRolesById(actorId)
                .filter(account -> account.isEnabled()
                        && Permission.permissionsFor(account.getRoles()).contains(Permission.VEHICLE_ALARM_READ))
                .isPresent();
    }

    @Override
    public boolean mayHandle(UUID actorId) {
        return has(actorId, Permission.VEHICLE_ALARM_HANDLE);
    }

    @Override
    public boolean mayReopen(UUID actorId) {
        return has(actorId, Permission.VEHICLE_ALARM_HANDLE)
                && hasAuthority(actorId, "ROLE_SYSTEM_ADMIN");
    }

    private boolean has(UUID actorId, Permission permission) {
        return hasAuthority(actorId, permission.name());
    }

    private boolean hasAuthority(UUID actorId, String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && actorId.equals(currentActor(authentication))
                && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    private UUID currentActor(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID actorId) return actorId;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
