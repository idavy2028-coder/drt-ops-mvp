package com.idavy.drtops.domain.terminal;

import com.idavy.drtops.common.ApiResponse;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
    private static final Set<String> TERMINAL_IDENTITY_CONSTRAINTS = Set.of(
            "uq_jt_terminals_terminal_phone_identity",
            "jt_terminals_terminal_phone_key",
            "jt_terminals_terminal_code_key");
    private static final Set<String> VEHICLE_IDENTIFIER_CONSTRAINTS = Set.of(
            "vehicles_plate_number_key");

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
    ResponseEntity<ApiResponse<Map<String, String>>> handleOptimisticLock(
            RuntimeException exception) {
        return conflict("terminal version conflict");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleDataIntegrity(
            DataIntegrityViolationException exception) {
        if (hasKnownConstraint(exception, ACTIVE_BINDING_CONSTRAINTS)) {
            return conflict("terminal or vehicle already has an active binding");
        }
        if (hasKnownConstraint(exception, TERMINAL_IDENTITY_CONSTRAINTS)) {
            return conflict("terminal identity is already in use");
        }
        if (hasKnownConstraint(exception, VEHICLE_IDENTIFIER_CONSTRAINTS)) {
            return conflict("vehicle identifier is already in use");
        }
        throw exception;
    }

    private static ResponseEntity<ApiResponse<Map<String, String>>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.ok(Map.of("message", message)));
    }

    private static boolean hasKnownConstraint(Throwable exception, Set<String> knownConstraints) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && isKnownConstraint(violation.getConstraintName(), knownConstraints)) {
                return true;
            }
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && containsKnownConstraintName(sqlException.getMessage(), knownConstraints)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownConstraint(String constraintName, Set<String> knownConstraints) {
        if (constraintName == null) {
            return false;
        }
        String normalized = constraintName.replace("\"", "")
                .substring(constraintName.replace("\"", "").lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        return knownConstraints.contains(normalized);
    }

    private static boolean containsKnownConstraintName(String message, Set<String> knownConstraints) {
        if (message == null) {
            return false;
        }
        return knownConstraints.stream().anyMatch(constraintName -> {
            String quotedConstraint = Pattern.quote(constraintName);
            Pattern postgres = Pattern.compile(
                    "(?i)unique\\s+constraint\\s+\"(?:[a-z0-9_]+\\.)?"
                            + quotedConstraint + "\"");
            Pattern h2 = Pattern.compile(
                    "(?i)unique\\s+index\\s+or\\s+primary\\s+key\\s+violation:\\s+"
                            + "\"?(?:[a-z0-9_]+\\.)?" + quotedConstraint
                            + "(?:_index_[a-z0-9_]+)?(?:\"|\\s|$)");
            return postgres.matcher(message).find() || h2.matcher(message).find();
        });
    }
}
