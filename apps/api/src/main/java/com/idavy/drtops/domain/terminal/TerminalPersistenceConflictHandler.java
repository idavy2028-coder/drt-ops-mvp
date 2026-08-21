package com.idavy.drtops.domain.terminal;

import com.idavy.drtops.common.ApiResponse;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = TerminalController.class)
public class TerminalPersistenceConflictHandler {

    private static final Set<String> ACTIVE_BINDING_CONSTRAINTS = Set.of(
            "uq_jt_terminal_vehicle_bindings_active_terminal",
            "uq_jt_terminal_vehicle_bindings_active_vehicle");

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
    ResponseEntity<ApiResponse<Map<String, String>>> handleOptimisticLock(
            RuntimeException exception) {
        return conflict("terminal version conflict");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleDataIntegrity(
            DataIntegrityViolationException exception) {
        if (!hasKnownActiveBindingConstraint(exception)) {
            throw exception;
        }
        return conflict("terminal or vehicle already has an active binding");
    }

    private static ResponseEntity<ApiResponse<Map<String, String>>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.ok(Map.of("message", message)));
    }

    private static boolean hasKnownActiveBindingConstraint(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && isKnownConstraint(violation.getConstraintName())) {
                return true;
            }
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && containsKnownConstraintName(sqlException.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownConstraint(String constraintName) {
        if (constraintName == null) {
            return false;
        }
        String normalized = constraintName.replace("\"", "")
                .substring(constraintName.replace("\"", "").lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        return ACTIVE_BINDING_CONSTRAINTS.contains(normalized);
    }

    private static boolean containsKnownConstraintName(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return ACTIVE_BINDING_CONSTRAINTS.stream().anyMatch(normalized::contains);
    }
}
