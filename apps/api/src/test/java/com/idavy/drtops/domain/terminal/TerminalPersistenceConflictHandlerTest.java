package com.idavy.drtops.domain.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class TerminalPersistenceConflictHandlerTest {

    private final TerminalPersistenceConflictHandler handler = new TerminalPersistenceConflictHandler();

    @Test
    void mapsConcreteOptimisticLockFailureToHttpConflict() {
        var response = handler.handleOptimisticLock(new ObjectOptimisticLockingFailureException(
                JtTerminal.class, UUID.fromString("11111111-1111-1111-1111-111111111111")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var jpaResponse = handler.handleOptimisticLock(new jakarta.persistence.OptimisticLockException("stale"));
        assertThat(jpaResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void mapsPersistenceConflictsTo409ThroughMvcExceptionResolution() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ConflictProbeController())
                .setControllerAdvice(handler)
                .build();

        mockMvc.perform(get("/test/terminal-conflict/optimistic"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/test/terminal-conflict/active-binding"))
                .andExpect(status().isConflict());
    }

    @Test
    void mapsOnlyKnownActiveBindingConstraintsToHttpConflict() {
        for (String constraint : java.util.List.of(
                "uq_jt_terminal_vehicle_bindings_active_terminal",
                "uq_jt_terminal_vehicle_bindings_active_vehicle")) {
            DataIntegrityViolationException postgres = violation(
                    "duplicate key violates unique constraint \"" + constraint + "\"");
            DataIntegrityViolationException h2 = violation(
                    "Unique index or primary key violation: PUBLIC." + constraint.toUpperCase());

            assertThat(handler.handleDataIntegrity(postgres).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(handler.handleDataIntegrity(h2).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void preservesOriginalSemanticsForUnrelatedIntegrityViolations() {
        DataIntegrityViolationException unrelated = violation(
                "duplicate key violates unique constraint \"unrelated_unique_constraint\"");

        assertThatThrownBy(() -> handler.handleDataIntegrity(unrelated)).isSameAs(unrelated);
    }

    private static DataIntegrityViolationException violation(String message) {
        return new DataIntegrityViolationException("terminal persistence failed", new SQLException(message, "23505"));
    }

    @RestController
    static class ConflictProbeController {

        @GetMapping("/test/terminal-conflict/optimistic")
        void optimistic() {
            throw new jakarta.persistence.OptimisticLockException("stale");
        }

        @GetMapping("/test/terminal-conflict/active-binding")
        void activeBinding() {
            throw violation("duplicate key violates unique constraint "
                    + "\"uq_jt_terminal_vehicle_bindings_active_vehicle\"");
        }
    }
}
