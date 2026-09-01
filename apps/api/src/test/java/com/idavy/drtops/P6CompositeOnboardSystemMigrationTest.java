package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.idavy.drtops.domain.onboard.OnboardDeviceCapability;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapabilityRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembership;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembershipRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfileRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignmentRepository;
import com.idavy.drtops.domain.onboard.OnboardSystem;
import com.idavy.drtops.domain.onboard.OnboardSystemRepository;
import com.idavy.drtops.domain.onboard.OnboardSystemRuntimeState;
import com.idavy.drtops.domain.onboard.OnboardSystemRuntimeStateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

class P6CompositeOnboardSystemMigrationTest {

    private static final String INTEGRATION_PROPERTY = "drt.integration.composite-onboard";

    @Test
    void v19BackfillsOneActiveSystemRuntimeAndMembershipAndRetainsLegacyBinding() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_backfill");
        flyway(postgres, schema, "18").migrate();
        LegacyIds ids;
        try (Connection connection = connection(postgres, schema)) {
            ids = insertLegacyActiveBinding(connection);
        }

        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            assertThat(tableExists(connection, "onboard_systems"))
                    .as("V19 onboard_systems table")
                    .isTrue();
            assertThat(queryCount(connection, """
                    select count(*)
                    from onboard_systems
                    where vehicle_id = ? and status = 'ACTIVE'
                    """, ids.vehicleId())).isEqualTo(1);
            assertThat(queryCount(connection, """
                    select count(*)
                    from onboard_system_runtime_state runtime
                    join onboard_systems system on system.id = runtime.onboard_system_id
                    where system.vehicle_id = ?
                    """, ids.vehicleId())).isEqualTo(1);
            assertThat(queryCount(connection, """
                    select count(*)
                    from onboard_device_memberships
                    where terminal_id = ? and status = 'ACTIVE' and valid_to is null
                    """, ids.terminalId())).isEqualTo(1);
            assertThat(queryCount(connection, """
                    select count(*)
                    from jt_terminal_vehicle_bindings
                    where id = ?
                    """, ids.bindingId())).isEqualTo(1);
        }
    }

    @Test
    void v19AllowsTwoDifferentTerminalsInOneSystem() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_multi_membership");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            assertThat(tableExists(connection, "onboard_device_memberships"))
                    .as("V19 onboard_device_memberships table")
                    .isTrue();
            UUID vehicleOne = insertVehicle(connection, "TASK1-MEMBER-1");
            UUID terminalOne = insertTerminal(connection, "013800000011", "T-TASK1-MEMBER-1");
            UUID terminalTwo = insertTerminal(connection, "013800000012", "T-TASK1-MEMBER-2");
            UUID systemOne = insertSystem(connection, vehicleOne);

            insertMembership(connection, systemOne, terminalOne);
            insertMembership(connection, systemOne, terminalTwo);

            assertThat(queryCount(connection, """
                    select count(*)
                    from onboard_device_memberships
                    where onboard_system_id = ? and status = 'ACTIVE' and valid_to is null
                    """, systemOne)).isEqualTo(2);
        }
    }

    @Test
    void v19RejectsOneTerminalInTwoActiveMemberships() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_unique_membership");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID vehicleOne = insertVehicle(connection, "TASK1-MEMBER-3");
            UUID vehicleTwo = insertVehicle(connection, "TASK1-MEMBER-4");
            UUID terminal = insertTerminal(connection, "013800000013", "T-TASK1-MEMBER-3");
            UUID systemOne = insertSystem(connection, vehicleOne);
            UUID systemTwo = insertSystem(connection, vehicleTwo);
            insertMembership(connection, systemOne, terminal);

            assertThatThrownBy(() -> insertMembership(connection, systemTwo, terminal))
                    .hasStackTraceContaining("uq_onboard_device_memberships_active_terminal");
        }
    }

    @Test
    void v19RejectsTwoActiveSystemsForOneVehicle() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_unique_system");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID vehicleId = insertVehicle(connection, "TASK1-SYSTEM-1");
            insertSystem(connection, vehicleId);

            assertThatThrownBy(() -> insertSystem(connection, vehicleId))
                    .hasStackTraceContaining("uq_onboard_systems_active_vehicle");
        }
    }

    @Test
    void v19RejectsTwoActiveFactsForOneTerminalCapability() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_unique_capability");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID terminalId = insertTerminal(connection, "013800000021", "T-TASK1-CAPABILITY");
            insertCapability(connection, terminalId, "JT808_LOCATION", "DECLARED");

            assertThatThrownBy(() -> insertCapability(
                    connection, terminalId, "JT808_LOCATION", "DECLARED"))
                    .hasStackTraceContaining(
                            "uq_onboard_device_capabilities_active_terminal_capability");
        }
    }

    @Test
    void v19RejectsTwoActiveProtocolProfilesForOneTerminal() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_unique_profile");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID terminalId = insertTerminal(connection, "013800000022", "T-TASK1-PROFILE");
            insertProtocolProfile(connection, terminalId, "ACTIVE", null, 10, 60);

            assertThatThrownBy(() -> insertProtocolProfile(
                    connection, terminalId, "ACTIVE", null, 20, 120))
                    .hasStackTraceContaining("uq_onboard_device_protocol_profiles_active_terminal");
        }
    }

    @Test
    void v19RejectsTwoActiveAssignmentsForOneSystemRole() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_unique_role");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID vehicleId = insertVehicle(connection, "TASK1-ROLE-1");
            UUID systemId = insertSystem(connection, vehicleId);
            UUID terminalOne = insertTerminal(connection, "013800000023", "T-TASK1-ROLE-1");
            UUID terminalTwo = insertTerminal(connection, "013800000024", "T-TASK1-ROLE-2");
            insertRole(connection, systemId, terminalOne, "LOCATION_PRIMARY", "ACTIVE", null);

            assertThatThrownBy(() -> insertRole(
                    connection, systemId, terminalTwo, "LOCATION_PRIMARY", "ACTIVE", null))
                    .hasStackTraceContaining("uq_onboard_device_role_assignments_active_system_role");
        }
    }

    @Test
    void v19RejectsInvalidEnumsIntervalsAndLifecycleTimestamps() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_checks");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID vehicleId = insertVehicle(connection, "TASK1-CHECK-1");
            UUID systemId = insertSystem(connection, vehicleId);
            UUID terminalId = insertTerminal(connection, "013800000025", "T-TASK1-CHECK");

            assertConstraintViolation(() -> execute(connection, """
                    insert into onboard_systems (id, vehicle_id, status, operating_mode)
                    values (?, ?, 'BROKEN', 'SAFETY_MONITOR_ONLY')
                    """, UUID.randomUUID(), insertVehicle(connection, "TASK1-CHECK-STATUS")),
                    "ck_onboard_systems_status");
            assertConstraintViolation(() -> execute(connection, """
                    insert into onboard_systems (id, vehicle_id, status, operating_mode)
                    values (?, ?, 'ACTIVE', 'BROKEN')
                    """, UUID.randomUUID(), insertVehicle(connection, "TASK1-CHECK-MODE")),
                    "ck_onboard_systems_operating_mode");
            assertConstraintViolation(() -> execute(connection, """
                    insert into onboard_device_memberships (
                      id, onboard_system_id, terminal_id, network_mode, status,
                      valid_from, added_reason
                    ) values (?, ?, ?, 'BROKEN', 'ACTIVE', now(), 'invalid network mode')
                    """, UUID.randomUUID(), systemId, terminalId),
                    "ck_onboard_device_memberships_network_mode");
            assertConstraintViolation(() -> execute(connection, """
                    insert into onboard_device_memberships (
                      id, onboard_system_id, terminal_id, network_mode, status,
                      valid_from, valid_to, added_reason
                    ) values (?, ?, ?, 'DIRECT_CELLULAR', 'ACTIVE', now(), now(), 'invalid active close')
                    """, UUID.randomUUID(), systemId, terminalId),
                    "ck_onboard_device_memberships_lifecycle");
            assertConstraintViolation(() -> execute(connection, """
                    insert into onboard_device_capabilities (
                      id, terminal_id, capability, status, reason
                    ) values (?, ?, 'BROKEN', 'DECLARED', 'invalid capability')
                    """, UUID.randomUUID(), terminalId),
                    "ck_onboard_device_capabilities_capability");
            assertConstraintViolation(() -> execute(connection, """
                    insert into onboard_device_capabilities (
                      id, terminal_id, capability, status, reason
                    ) values (?, ?, 'JT808_LOCATION', 'BROKEN', 'invalid capability status')
                    """, UUID.randomUUID(), terminalId),
                    "ck_onboard_device_capabilities_status");
            assertConstraintViolation(() -> execute(connection, """
                    insert into onboard_device_protocol_profiles (
                      id, terminal_id, transport_profile, business_profile,
                      safety_profile, media_profile, active_position_interval_seconds,
                      idle_position_interval_seconds, status, valid_from, reason
                    ) values (?, ?, 'BROKEN', 'NONE', 'NONE', 'NONE', 10, 60,
                      'ACTIVE', now(), 'invalid transport')
                    """, UUID.randomUUID(), terminalId),
                    "ck_onboard_device_protocol_profiles_transport");
            assertConstraintViolation(() -> execute(connection, """
                    insert into onboard_device_protocol_profiles (
                      id, terminal_id, transport_profile, business_profile,
                      safety_profile, media_profile, active_position_interval_seconds,
                      idle_position_interval_seconds, status, valid_from, reason
                    ) values (?, ?, 'JT808_2019', 'NONE', 'NONE', 'NONE', 0, 60,
                      'ACTIVE', now(), 'invalid interval')
                    """, UUID.randomUUID(), terminalId),
                    "ck_onboard_device_protocol_profiles_positive_intervals");
            assertConstraintViolation(() -> insertProtocolProfile(
                    connection, terminalId, "SUPERSEDED", null, 10, 60),
                    "ck_onboard_device_protocol_profiles_lifecycle");
            assertConstraintViolation(() -> execute(connection, """
                    insert into onboard_device_role_assignments (
                      id, onboard_system_id, terminal_id, role, status,
                      valid_from, assigned_reason
                    ) values (?, ?, ?, 'BROKEN', 'ACTIVE', now(), 'invalid role')
                    """, UUID.randomUUID(), systemId, terminalId),
                    "ck_onboard_device_role_assignments_role");
            assertConstraintViolation(() -> insertRole(
                    connection, systemId, terminalId, "LOCATION_PRIMARY", "REVOKED", null),
                    "ck_onboard_device_role_assignments_lifecycle");
        }
    }

    @Test
    void v19RejectsInvalidRuntimeRecoveryAndWarningShape() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_runtime_checks");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID streakSystemId = insertSystem(
                    connection, insertVehicle(connection, "TASK1-RUNTIME-STREAK"));
            UUID warningSystemId = insertSystem(
                    connection, insertVehicle(connection, "TASK1-RUNTIME-WARNING"));

            assertAll(
                    () -> assertConstraintViolation(() -> execute(connection, """
                            insert into onboard_system_runtime_state (
                              onboard_system_id, primary_recovery_streak,
                              primary_eligible, warning_codes
                            ) values (?, -1, true, '[]'::jsonb)
                            """, streakSystemId),
                            "ck_onboard_system_runtime_state_primary_recovery_streak"),
                    () -> assertConstraintViolation(() -> execute(connection, """
                            insert into onboard_system_runtime_state (
                              onboard_system_id, primary_recovery_streak,
                              primary_eligible, warning_codes
                            ) values (?, 0, true, '{}'::jsonb)
                            """, warningSystemId),
                            "ck_onboard_system_runtime_state_warning_codes"));
        }
    }

    @Test
    void v19RejectsNullObjectTooManyAndTooLongWarningCodes() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_warning_content");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID nullSystemId = insertSystem(
                    connection, insertVehicle(connection, "TASK1-WARNING-NULL"));
            UUID objectSystemId = insertSystem(
                    connection, insertVehicle(connection, "TASK1-WARNING-OBJECT"));
            UUID countSystemId = insertSystem(
                    connection, insertVehicle(connection, "TASK1-WARNING-COUNT"));
            UUID lengthSystemId = insertSystem(
                    connection, insertVehicle(connection, "TASK1-WARNING-LENGTH"));
            String thirtyThreeWarnings = "[" + "\"WARN\",".repeat(32) + "\"WARN\"]";
            String eightyOneCharacterWarning = "[\"" + "警".repeat(81) + "\"]";

            assertAll(
                    () -> assertInvalidWarningCodes(connection, nullSystemId, "[null]"),
                    () -> assertInvalidWarningCodes(connection, objectSystemId, "[{}]"),
                    () -> assertInvalidWarningCodes(connection, countSystemId, thirtyThreeWarnings),
                    () -> assertInvalidWarningCodes(connection, lengthSystemId, eightyOneCharacterWarning));
        }
    }

    @Test
    void v19RejectsInvalidMembershipAndRoleStatusesAndTimeRanges() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_membership_role_checks");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID systemId = insertSystem(
                    connection, insertVehicle(connection, "TASK1-HISTORY-CHECK"));
            UUID terminalId = insertTerminal(
                    connection, "013800000051", "T-TASK1-HISTORY-CHECK");
            execute(connection, """
                    alter table onboard_device_memberships
                    drop constraint ck_onboard_device_memberships_lifecycle
                    """);
            execute(connection, """
                    alter table onboard_device_role_assignments
                    drop constraint ck_onboard_device_role_assignments_lifecycle
                    """);

            assertAll(
                    () -> assertConstraintViolation(() -> execute(connection, """
                            insert into onboard_device_memberships (
                              id, onboard_system_id, terminal_id, network_mode, status,
                              valid_from, added_reason
                            ) values (?, ?, ?, 'DIRECT_CELLULAR', 'BROKEN',
                              timestamp with time zone '2026-08-29 10:00:00+08', 'invalid status')
                            """, UUID.randomUUID(), systemId, terminalId),
                            "ck_onboard_device_memberships_status"),
                    () -> assertConstraintViolation(() -> execute(connection, """
                            insert into onboard_device_memberships (
                              id, onboard_system_id, terminal_id, network_mode, status,
                              valid_from, valid_to, added_reason, removed_reason
                            ) values (?, ?, ?, 'DIRECT_CELLULAR', 'REMOVED',
                              timestamp with time zone '2026-08-29 10:00:00+08',
                              timestamp with time zone '2026-08-29 09:59:59+08',
                              'history fixture', 'invalid close time')
                            """, UUID.randomUUID(), systemId, terminalId),
                            "ck_onboard_device_memberships_time_range"),
                    () -> assertConstraintViolation(() -> execute(connection, """
                            insert into onboard_device_role_assignments (
                              id, onboard_system_id, terminal_id, role, status,
                              valid_from, assigned_reason
                            ) values (?, ?, ?, 'LOCATION_PRIMARY', 'BROKEN',
                              timestamp with time zone '2026-08-29 10:00:00+08', 'invalid status')
                            """, UUID.randomUUID(), systemId, terminalId),
                            "ck_onboard_device_role_assignments_status"),
                    () -> assertConstraintViolation(() -> execute(connection, """
                            insert into onboard_device_role_assignments (
                              id, onboard_system_id, terminal_id, role, status,
                              valid_from, valid_to, assigned_reason, revoked_reason
                            ) values (?, ?, ?, 'LOCATION_PRIMARY', 'REVOKED',
                              timestamp with time zone '2026-08-29 10:00:00+08',
                              timestamp with time zone '2026-08-29 09:59:59+08',
                              'history fixture', 'invalid close time')
                            """, UUID.randomUUID(), systemId, terminalId),
                            "ck_onboard_device_role_assignments_time_range"));
        }
    }

    @Test
    void v19RejectsVerifiedCapabilityWithoutCompleteEvidenceTimeAndActor() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_cap_verify");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID terminalId = insertTerminal(
                    connection, "013800000052", "T-TASK1-CAPABILITY-CHECK");
            UUID actorId = insertActor(connection);

            assertAll(
                    () -> assertConstraintViolation(() -> execute(connection, """
                            insert into onboard_device_capabilities (
                              id, terminal_id, capability, status, evidence_ref,
                              verified_at, verified_by, reason
                            ) values (?, ?, 'JT808_LOCATION', 'VERIFIED', null,
                              now(), ?, 'missing evidence')
                            """, UUID.randomUUID(), terminalId, actorId),
                            "ck_onboard_device_capabilities_verification"),
                    () -> assertConstraintViolation(() -> execute(connection, """
                            insert into onboard_device_capabilities (
                              id, terminal_id, capability, status, evidence_ref,
                              verified_at, verified_by, reason
                            ) values (?, ?, 'ADAS', 'VERIFIED', 'evidence/task-1',
                              null, ?, 'missing verification time')
                            """, UUID.randomUUID(), terminalId, actorId),
                            "ck_onboard_device_capabilities_verification"),
                    () -> assertConstraintViolation(() -> execute(connection, """
                            insert into onboard_device_capabilities (
                              id, terminal_id, capability, status, evidence_ref,
                              verified_at, verified_by, reason
                            ) values (?, ?, 'DMS', 'VERIFIED', 'evidence/task-1',
                              now(), null, 'missing verification actor')
                            """, UUID.randomUUID(), terminalId),
                            "ck_onboard_device_capabilities_verification"));
        }
    }

    @Test
    void v19RejectsInvalidProtocolProfilesIncludingIdleIntervalAndCloseTime() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_protocol_checks");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID businessTerminalId = insertTerminal(
                    connection, "013800000053", "T-TASK1-PROTOCOL-BUSINESS");
            UUID safetyTerminalId = insertTerminal(
                    connection, "013800000054", "T-TASK1-PROTOCOL-SAFETY");
            UUID mediaTerminalId = insertTerminal(
                    connection, "013800000055", "T-TASK1-PROTOCOL-MEDIA");
            UUID statusTerminalId = insertTerminal(
                    connection, "013800000056", "T-TASK1-PROTOCOL-STATUS");
            UUID intervalTerminalId = insertTerminal(
                    connection, "013800000057", "T-TASK1-PROTOCOL-INTERVAL");
            UUID timeRangeTerminalId = insertTerminal(
                    connection, "013800000058", "T-TASK1-PROTOCOL-TIME");
            execute(connection, """
                    alter table onboard_device_protocol_profiles
                    drop constraint ck_onboard_device_protocol_profiles_lifecycle
                    """);

            assertAll(
                    () -> assertInvalidProtocolProfile(
                            connection, businessTerminalId, "JT808_2019", "BROKEN", "NONE", "NONE",
                            10, 60, "ACTIVE", null,
                            "ck_onboard_device_protocol_profiles_business"),
                    () -> assertInvalidProtocolProfile(
                            connection, safetyTerminalId, "JT808_2019", "NONE", "BROKEN", "NONE",
                            10, 60, "ACTIVE", null,
                            "ck_onboard_device_protocol_profiles_safety"),
                    () -> assertInvalidProtocolProfile(
                            connection, mediaTerminalId, "JT808_2019", "NONE", "NONE", "BROKEN",
                            10, 60, "ACTIVE", null,
                            "ck_onboard_device_protocol_profiles_media"),
                    () -> assertInvalidProtocolProfile(
                            connection, statusTerminalId, "JT808_2019", "NONE", "NONE", "NONE",
                            10, 60, "BROKEN", null,
                            "ck_onboard_device_protocol_profiles_status"),
                    () -> assertInvalidProtocolProfile(
                            connection, intervalTerminalId, "JT808_2019", "NONE", "NONE", "NONE",
                            10, 0, "ACTIVE", null,
                            "ck_onboard_device_protocol_profiles_positive_intervals"),
                    () -> assertInvalidProtocolProfile(
                            connection, timeRangeTerminalId, "JT808_2019", "NONE", "NONE", "NONE",
                            10, 60, "SUPERSEDED",
                            OffsetDateTime.parse("2026-08-29T09:59:59+08:00"),
                            "ck_onboard_device_protocol_profiles_time_range"));
        }
    }

    @Test
    void v19RejectsInvalidVehicleLocationSourceRole() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_source_role_check");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            UUID vehicleId = insertVehicle(connection, "TASK1-SOURCE-ROLE-CHECK");
            UUID actorId = insertActor(connection);

            assertConstraintViolation(
                    () -> insertLocationEvent(connection, vehicleId, actorId, "BROKEN"),
                    "ck_vehicle_location_events_source_role");
        }
    }

    @Test
    void v19AddsLocationProvenanceColumns() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_provenance");
        flyway(postgres, schema, "19").migrate();

        try (Connection connection = connection(postgres, schema)) {
            assertThat(columnNames(connection, "vehicle_location_events"))
                    .contains("onboard_system_id", "source_role");
            assertThat(columnNames(connection, "vehicles"))
                    .contains("current_location_onboard_system_id");
        }
    }

    @Test
    void v19RollsBackSchemaAndHistoryWhenDuplicateLegacyBindingsViolateActiveSystemUniqueness()
            throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_duplicate_rollback");
        flyway(postgres, schema, "18").migrate();
        try (Connection connection = connection(postgres, schema)) {
            LegacyIds first = insertLegacyActiveBinding(connection);
            connection.createStatement().execute(
                    "drop index uq_jt_terminal_vehicle_bindings_active_vehicle");
            UUID secondTerminal = insertTerminal(
                    connection, "013800000031", "T-TASK1-DUPLICATE-BINDING");
            execute(connection, """
                    insert into jt_terminal_vehicle_bindings (
                      id, terminal_id, vehicle_id, valid_from, status, binding_reason
                    ) values (?, ?, ?, now(), 'ACTIVE', 'duplicate legacy fixture')
                    """, UUID.randomUUID(), secondTerminal, first.vehicleId());
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "19").migrate())
                .hasStackTraceContaining("uq_onboard_systems_active_vehicle");
        try (Connection connection = connection(postgres, schema)) {
            assertThat(tableExists(connection, "onboard_systems")).isFalse();
            assertThat(queryCount(connection, """
                    select count(*) from flyway_schema_history where version = '19'
                    """)).isZero();
            assertThat(queryCount(connection, """
                    select count(*) from jt_terminal_vehicle_bindings
                    where status = 'ACTIVE' and valid_to is null
                    """)).isEqualTo(2);
        }
    }

    @Test
    void v20RefusesContractWhenDispatchableVehicleHasNoDispatchRole() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_missing_dispatch");
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            insertDispatchableVehicleWithoutDispatchRole(connection);
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
                .hasStackTraceContaining("ONBOARD_CONTRACT_DISPATCH_ROLE_MISSING");
    }

    @Test
    void v20DropsSingleVehicleBindingIndexAndFreezesLegacyHistory() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_contract");
        UUID legacyHistoryId;
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            insertContractReadyDualDeviceSystem(connection);
            legacyHistoryId = insertLegacyHistory(connection);
        }

        flyway(postgres, schema, "20").migrate();

        try (Connection connection = connection(postgres, schema)) {
            assertThat(indexExists(connection,
                    "uq_jt_terminal_vehicle_bindings_active_vehicle")).isFalse();
            assertThat(indexExists(connection,
                    "uq_jt_terminal_vehicle_bindings_active_terminal")).isTrue();
            assertThat(queryCount(connection, """
                    select count(*) from jt_terminal_vehicle_bindings where id = ?
                    """, legacyHistoryId)).isEqualTo(1);
            assertThatThrownBy(() -> insertLegacyBinding(connection))
                    .hasMessageContaining("LEGACY_TERMINAL_BINDINGS_READ_ONLY");
            assertThatThrownBy(() -> updateLegacyBinding(connection, legacyHistoryId))
                    .hasMessageContaining("LEGACY_TERMINAL_BINDINGS_READ_ONLY");
            assertThatThrownBy(() -> deleteLegacyBinding(connection, legacyHistoryId))
                    .hasMessageContaining("LEGACY_TERMINAL_BINDINGS_READ_ONLY");
        }
    }

    @Test
    void v20RefusesActiveTerminalWithoutCurrentMembership() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_active_terminal_membership");
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            disableSeedDispatchableVehicles(connection);
            insertActiveTerminal(connection, "013800000085", "T-TASK8-ACTIVE-UNBOUND");
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
                .hasStackTraceContaining(
                        "ONBOARD_CONTRACT_ACTIVE_TERMINAL_MEMBERSHIP_MISSING");
    }

    @Test
    void v20RefusesActiveSystemWithoutCurrentMembership() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_active_system_membership");
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            disableSeedDispatchableVehicles(connection);
            insertSystem(
                    connection, insertVehicle(connection, "TASK8-ACTIVE-EMPTY"),
                    "SAFETY_MONITOR_ONLY");
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
                .hasStackTraceContaining(
                        "ONBOARD_CONTRACT_ACTIVE_SYSTEM_MEMBERSHIP_MISSING");
    }

    @Test
    void v20WaitsForInFlightCapabilityWriteAndRejectsCommittedInvalidContract()
            throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_capability_writer_lock");
        flyway(postgres, schema, "19").migrate();
        ContractReadyIds ids;
        try (Connection setup = connection(postgres, schema)) {
            ids = insertContractReadyDualDeviceSystem(setup);
        }

        CountDownLatch migrationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> migrationFuture = null;
        try (Connection writer = connection(postgres, schema)) {
            writer.setAutoCommit(false);
            try {
                execute(writer, """
                        update onboard_device_capabilities
                        set status = 'DISABLED',
                            reason = 'Task 8 concurrent disable fixture',
                            updated_at = now(),
                            version = version + 1
                        where terminal_id = ?
                          and capability = 'GBT28787_DISPATCH'
                          and status = 'VERIFIED'
                        """, ids.dispatchTerminalId());
                assertThat(queryCount(writer, """
                        select count(*)
                        from onboard_device_capabilities
                        where terminal_id = ?
                          and capability = 'GBT28787_DISPATCH'
                          and status = 'DISABLED'
                        """, ids.dispatchTerminalId())).isEqualTo(1);

                migrationFuture = executor.submit(() -> {
                    migrationStarted.countDown();
                    flyway(postgres, schema, "20").migrate();
                });
                assertThat(migrationStarted.await(5, TimeUnit.SECONDS))
                        .as("V20 migration worker started")
                        .isTrue();

                try {
                    migrationFuture.get(5, TimeUnit.SECONDS);
                    throw new AssertionError(
                            "V20 migration completed successfully before capability writer committed");
                } catch (TimeoutException expectedLockWait) {
                    // The migration must wait for the in-flight capability write to commit.
                } catch (ExecutionException unexpectedEarlyFailure) {
                    throw new AssertionError(
                            "V20 migration failed before capability writer committed instead of waiting",
                            unexpectedEarlyFailure.getCause());
                }

                writer.commit();
                Future<?> migrationAfterCommit = migrationFuture;
                assertThatThrownBy(() -> migrationAfterCommit.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasStackTraceContaining("ONBOARD_CONTRACT_ROLE_CAPABILITY_MISSING");
            } finally {
                writer.rollback();
            }
        } finally {
            if (migrationFuture != null && !migrationFuture.isDone()) {
                migrationFuture.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
                    .as("V20 migration executor terminated")
                    .isTrue();
        }
    }

    @Test
    void v20RefusesDispatchableVehicleWithoutActiveSystem() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_dispatch_system");
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            disableSeedDispatchableVehicles(connection);
            insertDispatchableVehicle(connection, "TASK8-NO-SYSTEM");
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
                .hasStackTraceContaining("ONBOARD_CONTRACT_DISPATCH_SYSTEM_MISSING");
    }

    @Test
    void v20RefusesDispatchableVehicleWithWrongOperatingMode() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_dispatch_mode");
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            disableSeedDispatchableVehicles(connection);
            UUID vehicleId = insertDispatchableVehicle(connection, "TASK8-WRONG-MODE");
            insertSystem(connection, vehicleId, "SAFETY_MONITOR_ONLY");
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
                .hasStackTraceContaining("ONBOARD_CONTRACT_DISPATCH_MODE_INVALID");
    }

    @Test
    void v20RefusesDispatchableVehicleWithoutLocationPrimaryRole() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_missing_primary");
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            disableSeedDispatchableVehicles(connection);
            UUID actorId = insertActor(connection);
            UUID vehicleId = insertDispatchableVehicle(connection, "TASK8-NO-PRIMARY");
            UUID systemId = insertSystem(connection, vehicleId, "DISPATCH_SERVICE");
            UUID terminalId = insertActiveTerminal(
                    connection, "013800000086", "T-TASK8-NO-PRIMARY");
            insertMembership(connection, systemId, terminalId);
            insertVerifiedCapability(
                    connection, terminalId, "GBT28787_DISPATCH", actorId);
            insertRole(connection, systemId, terminalId, "DISPATCH", "ACTIVE", null);
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
                .hasStackTraceContaining(
                        "ONBOARD_CONTRACT_LOCATION_PRIMARY_ROLE_MISSING");
    }

    @Test
    void v20RefusesPrimaryAndBackupOnSameTerminal() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_same_location_terminal");
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            ContractReadyIds ids = insertContractReadyDualDeviceSystem(connection);
            insertRole(
                    connection, ids.systemId(), ids.locationTerminalId(),
                    "LOCATION_BACKUP", "ACTIVE", null);
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
                .hasStackTraceContaining(
                        "ONBOARD_CONTRACT_LOCATION_TERMINALS_NOT_DISTINCT");
    }

    @Test
    void v20RefusesRoleTerminalWithoutCurrentMembershipInSameSystem() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_role_membership");
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            disableSeedDispatchableVehicles(connection);
            UUID actorId = insertActor(connection);
            UUID systemId = insertSystem(
                    connection, insertVehicle(connection, "TASK8-ROLE-NONMEMBER"),
                    "SAFETY_MONITOR_ONLY");
            UUID terminalId = insertTerminal(
                    connection, "013800000087", "T-TASK8-ROLE-NONMEMBER");
            insertVerifiedCapability(connection, terminalId, "VIDEO", actorId);
            insertRole(connection, systemId, terminalId, "VIDEO", "ACTIVE", null);
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
                .hasStackTraceContaining("ONBOARD_CONTRACT_ROLE_MEMBERSHIP_MISSING");
    }

    @Test
    void v20RefusesDispatchRoleWithoutMatchingVerifiedCapability() throws Exception {
        assertRoleCapabilityGate(
                "dispatch", "013800000088", "T-TASK8-CAP-DISPATCH",
                "DISPATCH", "JT808_LOCATION");
    }

    @Test
    void v20RefusesLocationRoleWithoutMatchingVerifiedCapability() throws Exception {
        assertRoleCapabilityGate(
                "location", "013800000089", "T-TASK8-CAP-LOCATION",
                "LOCATION_PRIMARY", "VIDEO");
    }

    @Test
    void v20RefusesActiveSafetyRoleWithoutMatchingVerifiedCapability() throws Exception {
        assertRoleCapabilityGate(
                "safety", "013800000090", "T-TASK8-CAP-SAFETY",
                "ACTIVE_SAFETY", "VIDEO");
    }

    @Test
    void v20RefusesVideoRoleWithoutMatchingVerifiedCapability() throws Exception {
        assertRoleCapabilityGate(
                "video", "013800000091", "T-TASK8-CAP-VIDEO",
                "VIDEO", "JT808_LOCATION");
    }

    @Test
    void v20RefusesWanUplinkOnSharedLanMembership() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_wan_network");
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            disableSeedDispatchableVehicles(connection);
            UUID systemId = insertSystem(
                    connection, insertVehicle(connection, "TASK8-WAN-SHARED"),
                    "SAFETY_MONITOR_ONLY");
            UUID terminalId = insertTerminal(
                    connection, "013800000092", "T-TASK8-WAN-SHARED");
            insertMembership(connection, systemId, terminalId, "SHARED_LAN_CLIENT");
            insertRole(connection, systemId, terminalId, "WAN_UPLINK", "ACTIVE", null);
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
                .hasStackTraceContaining(
                        "ONBOARD_CONTRACT_WAN_UPLINK_NETWORK_MODE_INVALID");
    }

    @Test
    void runtimeSourceRecoveryAndWarningsDoNotAdvanceConfigurationVersion() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OnboardSystem system = OnboardSystem.create(
                UUID.randomUUID(), OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY,
                UUID.randomUUID(), createdAt);
        OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                system.getId(), createdAt);
        long configurationVersion = system.getVersion();
        UUID sourceTerminalId = UUID.randomUUID();
        List<String> warnings = new ArrayList<>(List.of("PRIMARY_STALE"));

        runtime.selectLocationSource(sourceTerminalId, createdAt.plusSeconds(30));
        assertThat(runtime.recordPrimaryRecovery()).isEqualTo(1);
        runtime.replaceWarningCodes(warnings, createdAt.plusSeconds(31));
        warnings.add("CALLER_MUTATION");

        assertThat(system.getVersion()).isEqualTo(configurationVersion);
        assertThat(runtime.getActiveLocationTerminalId()).isEqualTo(sourceTerminalId);
        assertThat(runtime.getPrimaryRecoveryStreak()).isEqualTo(1);
        assertThat(runtime.getWarningCodes()).containsExactly("PRIMARY_STALE");
        assertThatThrownBy(() -> runtime.getWarningCodes().add("MUTATION"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void recordPrimaryValidAtRejectsLateEventThatWouldMovePrimaryTimeBackward() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OffsetDateTime latestPrimaryAt = createdAt.plusSeconds(30);
        OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                UUID.randomUUID(), createdAt);
        runtime.recordPrimaryValidAt(latestPrimaryAt);

        assertThatThrownBy(() -> runtime.recordPrimaryValidAt(createdAt.plusSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastPrimaryValidAt");
        assertThat(runtime.getLastPrimaryValidAt()).isEqualTo(latestPrimaryAt);
        assertThat(runtime.getUpdatedAt()).isEqualTo(latestPrimaryAt);
    }

    @Test
    void onboardSystemFailedCommandsLeaveEveryFieldUnchanged() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");

        assertAll(
                () -> {
                    OnboardSystem system = OnboardSystem.create(
                            UUID.randomUUID(), OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY,
                            UUID.randomUUID(), createdAt);
                    OnboardSystemSnapshot before = snapshot(system);
                    assertThatThrownBy(() -> system.changeOperatingMode(
                            OnboardSystem.OperatingMode.DISPATCH_SERVICE, null,
                            createdAt.plusMinutes(1)))
                            .isInstanceOf(NullPointerException.class);
                    assertThat(snapshot(system)).isEqualTo(before);
                },
                () -> {
                    OnboardSystem system = OnboardSystem.create(
                            UUID.randomUUID(), OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY,
                            UUID.randomUUID(), createdAt);
                    OnboardSystemSnapshot before = snapshot(system);
                    assertThatThrownBy(() -> system.touchConfiguration(
                            UUID.randomUUID(), createdAt.minusSeconds(1)))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(snapshot(system)).isEqualTo(before);
                },
                () -> {
                    OnboardSystem system = OnboardSystem.create(
                            UUID.randomUUID(), OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY,
                            UUID.randomUUID(), createdAt);
                    OnboardSystemSnapshot before = snapshot(system);
                    assertThatThrownBy(() -> system.suspend(
                            UUID.randomUUID(), createdAt.minusSeconds(1)))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(snapshot(system)).isEqualTo(before);
                });
    }

    @Test
    void runtimeStateFailedCommandsLeaveEveryFieldUnchanged() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");

        assertAll(
                () -> {
                    OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                            UUID.randomUUID(), createdAt);
                    runtime.selectLocationSource(UUID.randomUUID(), createdAt.plusSeconds(10));
                    runtime.recordPrimaryRecovery();
                    RuntimeStateSnapshot before = snapshot(runtime);
                    assertThatThrownBy(() -> runtime.selectLocationSource(
                            UUID.randomUUID(), createdAt.plusSeconds(5)))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(snapshot(runtime)).isEqualTo(before);
                },
                () -> {
                    OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                            UUID.randomUUID(), createdAt);
                    runtime.selectLocationSource(UUID.randomUUID(), createdAt.plusSeconds(10));
                    runtime.recordPrimaryRecovery();
                    RuntimeStateSnapshot before = snapshot(runtime);
                    assertThatThrownBy(() -> runtime.clearLocationSource(createdAt.plusSeconds(5)))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(snapshot(runtime)).isEqualTo(before);
                },
                () -> {
                    OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                            UUID.randomUUID(), createdAt);
                    runtime.replaceWarningCodes(List.of("PRIMARY_STALE"), createdAt.plusSeconds(10));
                    RuntimeStateSnapshot before = snapshot(runtime);
                    assertThatThrownBy(() -> runtime.replaceWarningCodes(
                            List.of("BACKUP_STALE"), createdAt.plusSeconds(5)))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(snapshot(runtime)).isEqualTo(before);
                },
                () -> {
                    OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                            UUID.randomUUID(), createdAt);
                    runtime.setPrimaryEligible(true, createdAt.plusSeconds(10));
                    RuntimeStateSnapshot before = snapshot(runtime);
                    assertThatThrownBy(() -> runtime.setPrimaryEligible(
                            false, createdAt.plusSeconds(5)))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(snapshot(runtime)).isEqualTo(before);
                });
    }

    @Test
    void warningCodesEnforceCountAndCharacterBoundariesAtomically() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                UUID.randomUUID(), createdAt);
        String eightyCharacters = "警".repeat(80);
        runtime.replaceWarningCodes(List.of(eightyCharacters), createdAt.plusSeconds(1));
        RuntimeStateSnapshot before = snapshot(runtime);

        assertAll(
                () -> {
                    assertThatThrownBy(() -> runtime.replaceWarningCodes(
                            List.of("警".repeat(81)), createdAt.plusSeconds(2)))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(snapshot(runtime)).isEqualTo(before);
                },
                () -> {
                    List<String> thirtyThree = java.util.stream.IntStream.range(0, 33)
                            .mapToObj(index -> "WARN_" + index)
                            .toList();
                    assertThatThrownBy(() -> runtime.replaceWarningCodes(
                            thirtyThree, createdAt.plusSeconds(2)))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(snapshot(runtime)).isEqualTo(before);
                });
    }

    @Test
    void warningCodeLimitsCountUnicodeCodePointsAtomically() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        String exactly80Emoji = "🚌".repeat(80);
        String tooLong81Emoji = "🚌".repeat(81);

        assertAll(
                () -> {
                    OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                            UUID.randomUUID(), createdAt);
                    runtime.replaceWarningCodes(List.of(exactly80Emoji), createdAt.plusSeconds(1));
                    assertThat(runtime.getWarningCodes()).containsExactly(exactly80Emoji);
                },
                () -> {
                    OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                            UUID.randomUUID(), createdAt);
                    runtime.replaceWarningCodes(List.of("PRIMARY_STALE"), createdAt.plusSeconds(1));
                    RuntimeStateSnapshot before = snapshot(runtime);
                    assertThatThrownBy(() -> runtime.replaceWarningCodes(
                            List.of(tooLong81Emoji), createdAt.plusSeconds(2)))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(snapshot(runtime)).isEqualTo(before);
                });
    }

    @Test
    void managedWarningCodesRoundTripValidStringsAtBoundary() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_warning_roundtrip");
        flyway(postgres, schema, "19").migrate();
        UUID vehicleId;
        UUID actorId;
        try (Connection connection = connection(postgres, schema)) {
            vehicleId = insertVehicle(connection, "TASK1-WARNING-ROUNDTRIP");
            actorId = insertActor(connection);
        }
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OnboardSystem system = OnboardSystem.create(
                vehicleId, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY, actorId, createdAt);
        OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                system.getId(), createdAt);
        List<String> expectedWarnings = List.of("警".repeat(80), "PRIMARY_STALE");
        runtime.replaceWarningCodes(expectedWarnings, createdAt.plusSeconds(1));
        String schemaJdbcUrl = postgres.jdbcUrl() + "?currentSchema=" + schema;

        try (var context = onboardJpaContext(postgres, schema, schemaJdbcUrl)) {
            EntityManagerFactory entityManagerFactory = context.getBean(EntityManagerFactory.class);
            EntityManager entityManager = entityManagerFactory.createEntityManager();
            try {
                transact(entityManager, () -> {
                    entityManager.persist(system);
                    entityManager.persist(runtime);
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
                List<String> reloaded = transact(entityManager, () -> {
                    List<String> value = entityManager.find(
                            OnboardSystemRuntimeState.class, system.getId()).getWarningCodes();
                    entityManager.clear();
                    return value;
                });
                assertThat(reloaded).containsExactlyElementsOf(expectedWarnings);
            } finally {
                entityManager.close();
            }
        }
    }

    @Test
    void membershipFailedRemovalLeavesEveryFieldUnchanged() {
        OffsetDateTime validFrom = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");

        assertAll(
                () -> assertMembershipFailureAtomic(
                        membership -> membership.remove("拆除设备", UUID.randomUUID(),
                                validFrom.minusSeconds(1)),
                        validFrom),
                () -> assertMembershipFailureAtomic(
                        membership -> membership.remove(" ", UUID.randomUUID(),
                                validFrom.plusMinutes(1)),
                        validFrom),
                () -> assertMembershipFailureAtomic(
                        membership -> membership.remove("拆除设备", null,
                                validFrom.plusMinutes(1)),
                        validFrom));
    }

    @Test
    void capabilityFailedTransitionsLeaveEveryFieldUnchanged() {
        OffsetDateTime declaredAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");

        assertAll(
                () -> assertCapabilityFailureAtomic(
                        capability -> capability.verify(" ", UUID.randomUUID(),
                                "验证能力", declaredAt.plusMinutes(1)), declaredAt),
                () -> assertCapabilityFailureAtomic(
                        capability -> capability.verify("evidence/task-1", null,
                                "验证能力", declaredAt.plusMinutes(1)), declaredAt),
                () -> assertCapabilityFailureAtomic(
                        capability -> capability.verify("evidence/task-1", UUID.randomUUID(),
                                "验证能力", declaredAt.minusSeconds(1)), declaredAt),
                () -> assertCapabilityFailureAtomic(
                        capability -> capability.verify("evidence/task-1", UUID.randomUUID(),
                                " ", declaredAt.plusMinutes(1)), declaredAt),
                () -> assertCapabilityFailureAtomic(
                        capability -> capability.disable(" ", declaredAt.plusMinutes(1)), declaredAt),
                () -> assertCapabilityFailureAtomic(
                        capability -> capability.disable("禁用能力", declaredAt.minusSeconds(1)), declaredAt));
    }

    @Test
    void verifiedCapabilityRejectsSecondVerificationWithoutChangingEvidence() {
        OffsetDateTime declaredAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        UUID firstActorId = UUID.randomUUID();
        OnboardDeviceCapability capability = OnboardDeviceCapability.declare(
                UUID.randomUUID(), OnboardDeviceCapability.Capability.JT808_LOCATION,
                "声明能力", declaredAt);
        capability.verify(
                "evidence/first", firstActorId, "首次验证", declaredAt.plusMinutes(1));
        CapabilitySnapshot before = snapshot(capability);

        assertThatThrownBy(() -> capability.verify(
                "evidence/second", UUID.randomUUID(), "重复验证", declaredAt.plusMinutes(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DECLARED");
        assertThat(snapshot(capability)).isEqualTo(before);
    }

    @Test
    void managedVerifiedCapabilityRejectsSecondVerificationWithoutUpdate() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_verify_once");
        flyway(postgres, schema, "19").migrate();
        UUID terminalId;
        UUID firstActorId;
        UUID secondActorId;
        try (Connection connection = connection(postgres, schema)) {
            terminalId = insertTerminal(connection, "013800000072", "T-TASK1-VERIFY-ONCE");
            firstActorId = insertActor(connection);
            secondActorId = insertActor(connection);
        }
        OffsetDateTime declaredAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OnboardDeviceCapability capability = OnboardDeviceCapability.declare(
                terminalId, OnboardDeviceCapability.Capability.JT808_LOCATION,
                "声明能力", declaredAt);
        capability.verify(
                "evidence/first", firstActorId, "首次验证", declaredAt.plusMinutes(1));
        String schemaJdbcUrl = postgres.jdbcUrl() + "?currentSchema=" + schema;

        try (var context = onboardJpaContext(postgres, schema, schemaJdbcUrl)) {
            EntityManagerFactory entityManagerFactory = context.getBean(EntityManagerFactory.class);
            EntityManager entityManager = entityManagerFactory.createEntityManager();
            try {
                transact(entityManager, () -> {
                    entityManager.persist(capability);
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
                CapabilitySnapshot before = transact(entityManager, () -> {
                    CapabilitySnapshot value = snapshot(entityManager.find(
                            OnboardDeviceCapability.class, capability.getId()));
                    entityManager.clear();
                    return value;
                });
                boolean[] rejected = {false};
                transact(entityManager, () -> {
                    OnboardDeviceCapability managed = entityManager.find(
                            OnboardDeviceCapability.class, capability.getId());
                    try {
                        managed.verify(
                                "evidence/second", secondActorId, "重复验证",
                                declaredAt.plusMinutes(2));
                    } catch (IllegalStateException expected) {
                        rejected[0] = true;
                    }
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
                CapabilitySnapshot reloaded = transact(entityManager, () -> {
                    CapabilitySnapshot value = snapshot(entityManager.find(
                            OnboardDeviceCapability.class, capability.getId()));
                    entityManager.clear();
                    return value;
                });
                assertAll(
                        () -> assertThat(rejected[0]).isTrue(),
                        () -> assertThat(reloaded).isEqualTo(before));
            } finally {
                entityManager.close();
            }
        }
    }

    @Test
    void currentRepositoryContractsExposeOnlyEnumParameters() {
        List<java.lang.reflect.Method> capabilityMethods = java.util.Arrays.stream(
                        OnboardDeviceCapabilityRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("findCurrentByTerminalIdAndCapability"))
                .toList();
        List<java.lang.reflect.Method> roleMethods = java.util.Arrays.stream(
                        OnboardDeviceRoleAssignmentRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("findActiveByOnboardSystemIdAndRole"))
                .toList();

        assertAll(
                () -> {
                    assertThat(capabilityMethods).hasSize(1);
                    assertThat(capabilityMethods.get(0).getParameterTypes()).containsExactly(
                            UUID.class, OnboardDeviceCapability.Capability.class);
                },
                () -> {
                    assertThat(roleMethods).hasSize(1);
                    assertThat(roleMethods.get(0).getParameterTypes()).containsExactly(
                            UUID.class, OnboardDeviceRoleAssignment.Role.class);
                });
    }

    @Test
    void fixedRepositoryContractsReturnCurrentAndCompleteHistory() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_repository_history");
        flyway(postgres, schema, "19").migrate();
        UUID vehicleId;
        UUID terminalId;
        UUID actorId;
        try (Connection connection = connection(postgres, schema)) {
            vehicleId = insertVehicle(connection, "TASK1-REPOSITORY-HISTORY");
            terminalId = insertTerminal(connection, "013800000073", "T-TASK1-REPOSITORY-HISTORY");
            actorId = insertActor(connection);
        }
        OffsetDateTime base = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OnboardSystem historySystemOne = OnboardSystem.create(
                vehicleId, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY, actorId, base);
        historySystemOne.suspend(actorId, base.plusMinutes(1));
        OnboardSystem historySystemTwo = OnboardSystem.create(
                vehicleId, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY, actorId, base.plusMinutes(2));
        historySystemTwo.suspend(actorId, base.plusMinutes(3));
        OnboardSystem currentSystem = OnboardSystem.create(
                vehicleId, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY, actorId, base.plusMinutes(4));

        OnboardDeviceMembership membershipOne = OnboardDeviceMembership.join(
                currentSystem.getId(), terminalId,
                OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                "成员历史一", actorId, base);
        membershipOne.remove("移除成员一", actorId, base.plusMinutes(1));
        OnboardDeviceMembership membershipTwo = OnboardDeviceMembership.join(
                currentSystem.getId(), terminalId,
                OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                "成员历史二", actorId, base.plusMinutes(2));
        membershipTwo.remove("移除成员二", actorId, base.plusMinutes(3));
        OnboardDeviceMembership currentMembership = OnboardDeviceMembership.join(
                currentSystem.getId(), terminalId,
                OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                "当前成员", actorId, base.plusMinutes(4));

        OnboardDeviceCapability capabilityOne = OnboardDeviceCapability.declare(
                terminalId, OnboardDeviceCapability.Capability.JT808_LOCATION,
                "能力历史一", base);
        capabilityOne.disable("禁用能力一", base.plusMinutes(1));
        OnboardDeviceCapability capabilityTwo = OnboardDeviceCapability.declare(
                terminalId, OnboardDeviceCapability.Capability.JT808_LOCATION,
                "能力历史二", base.plusMinutes(2));
        capabilityTwo.disable("禁用能力二", base.plusMinutes(3));
        OnboardDeviceCapability currentCapability = OnboardDeviceCapability.declare(
                terminalId, OnboardDeviceCapability.Capability.JT808_LOCATION,
                "当前能力", base.plusMinutes(4));

        OnboardDeviceProtocolProfile profileOne = protocolProfile(
                terminalId, "档案历史一", actorId, base);
        profileOne.supersede("关闭档案一", actorId, base.plusMinutes(1));
        OnboardDeviceProtocolProfile profileTwo = protocolProfile(
                terminalId, "档案历史二", actorId, base.plusMinutes(2));
        profileTwo.supersede("关闭档案二", actorId, base.plusMinutes(3));
        OnboardDeviceProtocolProfile currentProfile = protocolProfile(
                terminalId, "当前档案", actorId, base.plusMinutes(4));

        OnboardDeviceRoleAssignment roleOne = OnboardDeviceRoleAssignment.assign(
                currentSystem.getId(), terminalId,
                OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                "角色历史一", actorId, base);
        roleOne.revoke("撤销角色一", actorId, base.plusMinutes(1));
        OnboardDeviceRoleAssignment roleTwo = OnboardDeviceRoleAssignment.assign(
                currentSystem.getId(), terminalId,
                OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                "角色历史二", actorId, base.plusMinutes(2));
        roleTwo.revoke("撤销角色二", actorId, base.plusMinutes(3));
        OnboardDeviceRoleAssignment currentRole = OnboardDeviceRoleAssignment.assign(
                currentSystem.getId(), terminalId,
                OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                "当前角色", actorId, base.plusMinutes(4));

        String schemaJdbcUrl = postgres.jdbcUrl() + "?currentSchema=" + schema;
        try (var context = onboardJpaContext(postgres, schema, schemaJdbcUrl)) {
            EntityManagerFactory entityManagerFactory = context.getBean(EntityManagerFactory.class);
            EntityManager entityManager = entityManagerFactory.createEntityManager();
            try {
                transact(entityManager, () -> {
                    List.of(historySystemOne, historySystemTwo, currentSystem,
                                    membershipOne, membershipTwo, currentMembership,
                                    capabilityOne, capabilityTwo, currentCapability,
                                    profileOne, profileTwo, currentProfile,
                                    roleOne, roleTwo, currentRole)
                            .forEach(entityManager::persist);
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
            } finally {
                entityManager.close();
            }

            OnboardSystemRepository systems = context.getBean(OnboardSystemRepository.class);
            OnboardDeviceMembershipRepository memberships =
                    context.getBean(OnboardDeviceMembershipRepository.class);
            OnboardDeviceCapabilityRepository capabilities =
                    context.getBean(OnboardDeviceCapabilityRepository.class);
            OnboardDeviceProtocolProfileRepository profiles =
                    context.getBean(OnboardDeviceProtocolProfileRepository.class);
            OnboardDeviceRoleAssignmentRepository roles =
                    context.getBean(OnboardDeviceRoleAssignmentRepository.class);

            assertAll(
                    () -> assertThat(systems.findActiveByVehicleId(vehicleId))
                            .get().extracting(OnboardSystem::getId).isEqualTo(currentSystem.getId()),
                    () -> assertThat(systems.findHistoryByVehicleIdOrderByCreatedAtAsc(vehicleId))
                            .extracting(OnboardSystem::getId)
                            .containsExactly(historySystemOne.getId(), historySystemTwo.getId(), currentSystem.getId()),
                    () -> assertThat(memberships.findActiveByTerminalId(terminalId))
                            .get().extracting(OnboardDeviceMembership::getId).isEqualTo(currentMembership.getId()),
                    () -> assertThat(memberships.findHistoryByTerminalIdOrderByValidFromAsc(terminalId))
                            .extracting(OnboardDeviceMembership::getId)
                            .containsExactly(membershipOne.getId(), membershipTwo.getId(), currentMembership.getId()),
                    () -> assertThat(capabilities.findCurrentByTerminalIdAndCapability(
                                    terminalId, OnboardDeviceCapability.Capability.JT808_LOCATION))
                            .get().extracting(OnboardDeviceCapability::getId).isEqualTo(currentCapability.getId()),
                    () -> assertThat(capabilities.findHistoryByTerminalIdAndCapabilityOrderByCreatedAtAsc(
                                    terminalId, OnboardDeviceCapability.Capability.JT808_LOCATION))
                            .extracting(OnboardDeviceCapability::getId)
                            .containsExactly(capabilityOne.getId(), capabilityTwo.getId(), currentCapability.getId()),
                    () -> assertThat(profiles.findActiveByTerminalId(terminalId))
                            .get().extracting(OnboardDeviceProtocolProfile::getId).isEqualTo(currentProfile.getId()),
                    () -> assertThat(profiles.findHistoryByTerminalIdOrderByValidFromAsc(terminalId))
                            .extracting(OnboardDeviceProtocolProfile::getId)
                            .containsExactly(profileOne.getId(), profileTwo.getId(), currentProfile.getId()),
                    () -> assertThat(roles.findActiveByOnboardSystemIdAndRole(
                                    currentSystem.getId(), OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY))
                            .get().extracting(OnboardDeviceRoleAssignment::getId).isEqualTo(currentRole.getId()),
                    () -> assertThat(roles.findHistoryByOnboardSystemIdAndRoleOrderByValidFromAsc(
                                    currentSystem.getId(), OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY))
                            .extracting(OnboardDeviceRoleAssignment::getId)
                            .containsExactly(roleOne.getId(), roleTwo.getId(), currentRole.getId()));
        }
    }

    @Test
    void roleAssignmentFailedRevokeLeavesEveryFieldUnchanged() {
        OffsetDateTime validFrom = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");

        assertAll(
                () -> assertRoleFailureAtomic(
                        role -> role.revoke("撤销角色", UUID.randomUUID(),
                                validFrom.minusSeconds(1)), validFrom),
                () -> assertRoleFailureAtomic(
                        role -> role.revoke(" ", UUID.randomUUID(),
                                validFrom.plusMinutes(1)), validFrom),
                () -> assertRoleFailureAtomic(
                        role -> role.revoke("撤销角色", null,
                                validFrom.plusMinutes(1)), validFrom));
    }

    @Test
    void auditTextConstructorsAccept500AndReject501UnicodeCharacters() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        String exactly500 = "理".repeat(500);
        String tooLong501 = "理".repeat(501);

        OnboardDeviceMembership membership = OnboardDeviceMembership.join(
                UUID.randomUUID(), UUID.randomUUID(),
                OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                exactly500, UUID.randomUUID(), createdAt);
        OnboardDeviceCapability capability = OnboardDeviceCapability.declare(
                UUID.randomUUID(), OnboardDeviceCapability.Capability.JT808_LOCATION,
                exactly500, createdAt);
        OnboardDeviceProtocolProfile profile = protocolProfile(
                UUID.randomUUID(), exactly500, UUID.randomUUID(), createdAt);
        OnboardDeviceRoleAssignment role = OnboardDeviceRoleAssignment.assign(
                UUID.randomUUID(), UUID.randomUUID(),
                OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                exactly500, UUID.randomUUID(), createdAt);

        assertAll(
                () -> assertThat(membership.getAddedReason()).hasSize(500),
                () -> assertThat(capability.getReason()).hasSize(500),
                () -> assertThat(profile.getReason()).hasSize(500),
                () -> assertThat(role.getAssignedReason()).hasSize(500),
                () -> assertThatThrownBy(() -> OnboardDeviceMembership.join(
                        UUID.randomUUID(), UUID.randomUUID(),
                        OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                        tooLong501, UUID.randomUUID(), createdAt))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> OnboardDeviceCapability.declare(
                        UUID.randomUUID(), OnboardDeviceCapability.Capability.JT808_LOCATION,
                        tooLong501, createdAt))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> protocolProfile(
                        UUID.randomUUID(), tooLong501, UUID.randomUUID(), createdAt))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> OnboardDeviceRoleAssignment.assign(
                        UUID.randomUUID(), UUID.randomUUID(),
                        OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                        tooLong501, UUID.randomUUID(), createdAt))
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void auditTextCommandsReject501AtomicallyAndAccept500() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OffsetDateTime changedAt = createdAt.plusMinutes(1);
        String exactly500 = "理".repeat(500);
        String tooLong501 = "理".repeat(501);

        assertAll(
                () -> {
                    OnboardDeviceMembership membership = OnboardDeviceMembership.join(
                            UUID.randomUUID(), UUID.randomUUID(),
                            OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                            "安装", UUID.randomUUID(), createdAt);
                    membership.remove(exactly500, UUID.randomUUID(), changedAt);
                    assertThat(membership.getRemovedReason()).hasSize(500);
                },
                () -> {
                    OnboardDeviceCapability capability = OnboardDeviceCapability.declare(
                            UUID.randomUUID(), OnboardDeviceCapability.Capability.JT808_LOCATION,
                            "声明", createdAt);
                    capability.verify(exactly500, UUID.randomUUID(), exactly500, changedAt);
                    assertThat(capability.getEvidenceRef()).hasSize(500);
                    assertThat(capability.getReason()).hasSize(500);
                },
                () -> {
                    OnboardDeviceProtocolProfile profile = protocolProfile(
                            UUID.randomUUID(), "登记", UUID.randomUUID(), createdAt);
                    profile.supersede(exactly500, UUID.randomUUID(), changedAt);
                    assertThat(profile.getStatus())
                            .isEqualTo(OnboardDeviceProtocolProfile.Status.SUPERSEDED);
                },
                () -> {
                    OnboardDeviceRoleAssignment role = OnboardDeviceRoleAssignment.assign(
                            UUID.randomUUID(), UUID.randomUUID(),
                            OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                            "分配", UUID.randomUUID(), createdAt);
                    role.revoke(exactly500, UUID.randomUUID(), changedAt);
                    assertThat(role.getRevokedReason()).hasSize(500);
                },
                () -> assertMembershipTextFailureAtomic(tooLong501, createdAt, changedAt),
                () -> assertCapabilityVerifyTextFailureAtomic(
                        tooLong501, "验证", createdAt, changedAt),
                () -> assertCapabilityVerifyTextFailureAtomic(
                        "evidence", tooLong501, createdAt, changedAt),
                () -> assertCapabilityDisableTextFailureAtomic(tooLong501, createdAt, changedAt),
                () -> assertProfileTextFailureAtomic(tooLong501, createdAt, changedAt),
                () -> assertRoleTextFailureAtomic(tooLong501, createdAt, changedAt));
    }

    @Test
    void auditTextLimitsCountUnicodeCodePointsAtomically() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OffsetDateTime changedAt = createdAt.plusMinutes(1);
        String exactly500Emoji = "🚌".repeat(500);
        String tooLong501Emoji = "🚌".repeat(501);

        assertAll(
                () -> {
                    OnboardDeviceMembership membership = OnboardDeviceMembership.join(
                            UUID.randomUUID(), UUID.randomUUID(),
                            OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                            exactly500Emoji, UUID.randomUUID(), createdAt);
                    assertThat(membership.getAddedReason()).isEqualTo(exactly500Emoji);
                },
                () -> {
                    OnboardDeviceCapability capability = OnboardDeviceCapability.declare(
                            UUID.randomUUID(), OnboardDeviceCapability.Capability.JT808_LOCATION,
                            "声明", createdAt);
                    capability.verify(
                            exactly500Emoji, UUID.randomUUID(), exactly500Emoji, changedAt);
                    assertThat(capability.getEvidenceRef()).isEqualTo(exactly500Emoji);
                    assertThat(capability.getReason()).isEqualTo(exactly500Emoji);
                },
                () -> assertThatThrownBy(() -> OnboardDeviceMembership.join(
                        UUID.randomUUID(), UUID.randomUUID(),
                        OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                        tooLong501Emoji, UUID.randomUUID(), createdAt))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertCapabilityVerifyTextFailureAtomic(
                        tooLong501Emoji, "验证", createdAt, changedAt),
                () -> assertCapabilityVerifyTextFailureAtomic(
                        "evidence", tooLong501Emoji, createdAt, changedAt));
    }

    @Test
    void managedMembershipFailureDoesNotFlushPartialUpdate() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_atomic_membership");
        flyway(postgres, schema, "19").migrate();
        UUID vehicleId;
        UUID terminalId;
        UUID actorId;
        try (Connection connection = connection(postgres, schema)) {
            vehicleId = insertVehicle(connection, "TASK1-ATOMIC-MEMBERSHIP");
            terminalId = insertTerminal(connection, "013800000071", "T-TASK1-ATOMIC-MEMBERSHIP");
            actorId = insertActor(connection);
        }
        OffsetDateTime validFrom = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OnboardSystem system = OnboardSystem.create(
                vehicleId, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY, actorId, validFrom);
        OnboardDeviceMembership membership = OnboardDeviceMembership.join(
                system.getId(), terminalId, OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                "安装设备", actorId, validFrom);
        String schemaJdbcUrl = postgres.jdbcUrl() + "?currentSchema=" + schema;

        try (var context = onboardJpaContext(postgres, schema, schemaJdbcUrl)) {
            EntityManagerFactory entityManagerFactory = context.getBean(EntityManagerFactory.class);
            EntityManager entityManager = entityManagerFactory.createEntityManager();
            try {
                transact(entityManager, () -> {
                    entityManager.persist(system);
                    entityManager.persist(membership);
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
                MembershipSnapshot before = transact(entityManager, () -> {
                    MembershipSnapshot value = snapshot(entityManager.find(
                            OnboardDeviceMembership.class, membership.getId()));
                    entityManager.clear();
                    return value;
                });
                transact(entityManager, () -> {
                    OnboardDeviceMembership managed = entityManager.find(
                            OnboardDeviceMembership.class, membership.getId());
                    assertThatThrownBy(() -> managed.remove(
                            " ", actorId, validFrom.plusMinutes(1)))
                            .isInstanceOf(IllegalArgumentException.class);
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
                MembershipSnapshot reloaded = transact(entityManager, () -> {
                    MembershipSnapshot value = snapshot(entityManager.find(
                            OnboardDeviceMembership.class, membership.getId()));
                    entityManager.clear();
                    return value;
                });
                assertThat(reloaded).isEqualTo(before);
            } finally {
                entityManager.close();
            }
        }
    }

    @Test
    void onboardSystemCanRetireOnceAndRecordsConfigurationActorAndTime() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OffsetDateTime retiredAt = createdAt.plusHours(1);
        UUID retirementActor = UUID.randomUUID();
        OnboardSystem system = OnboardSystem.create(
                UUID.randomUUID(), OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY,
                UUID.randomUUID(), createdAt);

        system.retire(retirementActor, retiredAt);

        assertThat(system.getStatus()).isEqualTo(OnboardSystem.Status.RETIRED);
        assertThat(system.getUpdatedBy()).isEqualTo(retirementActor);
        assertThat(system.getUpdatedAt()).isEqualTo(retiredAt);
        assertThatThrownBy(() -> system.retire(retirementActor, retiredAt.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void historyEntitiesCloseRowsWithoutRewritingProtocolProfileProvenance() {
        OffsetDateTime validFrom = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        OffsetDateTime validTo = validFrom.plusHours(1);
        UUID systemId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        UUID creationActorId = UUID.randomUUID();
        UUID changeActorId = UUID.randomUUID();
        OnboardDeviceMembership membership = OnboardDeviceMembership.join(
                systemId, terminalId, OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                "安装设备", creationActorId, validFrom);
        OnboardDeviceProtocolProfile profile = OnboardDeviceProtocolProfile.activate(
                terminalId,
                OnboardDeviceProtocolProfile.TransportProfile.JT808_2019,
                OnboardDeviceProtocolProfile.BusinessProfile.NONE,
                OnboardDeviceProtocolProfile.SafetyProfile.JSATL12_2017,
                OnboardDeviceProtocolProfile.MediaProfile.JT1078_2016,
                10, 60, "登记协议档案", creationActorId, validFrom);
        OnboardDeviceRoleAssignment role = OnboardDeviceRoleAssignment.assign(
                systemId, terminalId, OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                "分配位置主源", creationActorId, validFrom);

        membership.remove("拆除设备", changeActorId, validTo);
        profile.supersede("更新协议档案", changeActorId, validTo);
        OnboardDeviceProtocolProfile replacement = OnboardDeviceProtocolProfile.activate(
                terminalId,
                OnboardDeviceProtocolProfile.TransportProfile.JT808_2019,
                OnboardDeviceProtocolProfile.BusinessProfile.NONE,
                OnboardDeviceProtocolProfile.SafetyProfile.NONE,
                OnboardDeviceProtocolProfile.MediaProfile.NONE,
                20, 120, "更新协议档案", changeActorId, validTo);
        role.revoke("撤销位置主源", changeActorId, validTo);

        assertThat(membership.getStatus()).isEqualTo(OnboardDeviceMembership.Status.REMOVED);
        assertThat(membership.getValidTo()).isEqualTo(validTo);
        assertThat(membership.getRemovedReason()).isEqualTo("拆除设备");
        assertThat(profile.getStatus()).isEqualTo(OnboardDeviceProtocolProfile.Status.SUPERSEDED);
        assertThat(profile.getValidTo()).isEqualTo(validTo);
        assertThat(profile.getReason()).isEqualTo("登记协议档案");
        assertThat(profile.getActorId()).isEqualTo(creationActorId);
        assertThat(replacement.getId()).isNotEqualTo(profile.getId());
        assertThat(replacement.getReason()).isEqualTo("更新协议档案");
        assertThat(replacement.getActorId()).isEqualTo(changeActorId);
        assertThat(role.getStatus()).isEqualTo(OnboardDeviceRoleAssignment.Status.REVOKED);
        assertThat(role.getValidTo()).isEqualTo(validTo);
        assertThat(role.getRevokedReason()).isEqualTo("撤销位置主源");
    }

    @Test
    void v19JpaMappingsAndRepositoryQueriesValidateAgainstPostgres() {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_jpa");
        flyway(postgres, schema, "19").migrate();
        String schemaJdbcUrl = postgres.jdbcUrl() + "?currentSchema=" + schema;
        UUID vehicleId;
        UUID terminalId;
        UUID actorId;
        try (Connection connection = connection(postgres, schema)) {
            vehicleId = insertVehicle(connection, "TASK1-JPA-1");
            terminalId = insertTerminal(connection, "013800000041", "T-TASK1-JPA");
            actorId = insertActor(connection);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create JPA smoke fixture", exception);
        }

        try (var context = onboardJpaContext(postgres, schema, schemaJdbcUrl)) {
            OnboardSystemRepository systems = context.getBean(OnboardSystemRepository.class);
            OnboardSystemRuntimeStateRepository runtimes =
                    context.getBean(OnboardSystemRuntimeStateRepository.class);
            OnboardDeviceMembershipRepository memberships =
                    context.getBean(OnboardDeviceMembershipRepository.class);
            OnboardDeviceCapabilityRepository capabilities =
                    context.getBean(OnboardDeviceCapabilityRepository.class);
            OnboardDeviceProtocolProfileRepository profiles =
                    context.getBean(OnboardDeviceProtocolProfileRepository.class);
            OnboardDeviceRoleAssignmentRepository roles =
                    context.getBean(OnboardDeviceRoleAssignmentRepository.class);
            OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T12:00:00+08:00");

            OnboardSystem system = systems.saveAndFlush(OnboardSystem.create(
                    vehicleId, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY,
                    actorId, createdAt));
            runtimes.saveAndFlush(OnboardSystemRuntimeState.initialize(system.getId(), createdAt));
            memberships.saveAndFlush(OnboardDeviceMembership.join(
                    system.getId(), terminalId,
                    OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                    "JPA 烟测成员", actorId, createdAt));
            capabilities.saveAndFlush(OnboardDeviceCapability.declare(
                    terminalId, OnboardDeviceCapability.Capability.JT808_LOCATION,
                    "JPA 烟测能力", createdAt));
            profiles.saveAndFlush(OnboardDeviceProtocolProfile.activate(
                    terminalId,
                    OnboardDeviceProtocolProfile.TransportProfile.JT808_2019,
                    OnboardDeviceProtocolProfile.BusinessProfile.NONE,
                    OnboardDeviceProtocolProfile.SafetyProfile.NONE,
                    OnboardDeviceProtocolProfile.MediaProfile.NONE,
                    10, 60, "JPA 烟测协议", actorId, createdAt));
            roles.saveAndFlush(OnboardDeviceRoleAssignment.assign(
                    system.getId(), terminalId,
                    OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                    "JPA 烟测角色", actorId, createdAt));

            assertThat(systems.findActiveByVehicleId(vehicleId)).isPresent();
            assertThat(runtimes.findById(system.getId())).isPresent();
            assertThat(memberships.findActiveByTerminalId(terminalId)).isPresent();
            assertThat(capabilities.findCurrentByTerminalIdAndCapability(
                    terminalId, OnboardDeviceCapability.Capability.JT808_LOCATION)).isPresent();
            assertThat(profiles.findActiveByTerminalId(terminalId)).isPresent();
            assertThat(roles.findActiveByOnboardSystemIdAndRole(
                    system.getId(), OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY)).isPresent();
        }
    }

    @Test
    void v19PersistsConfigurationAndRuntimeVersionsIndependently() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_version_isolation");
        flyway(postgres, schema, "19").migrate();
        UUID vehicleId;
        UUID terminalId;
        UUID actorId;
        try (Connection connection = connection(postgres, schema)) {
            vehicleId = insertVehicle(connection, "TASK1-VERSION-ISOLATION");
            terminalId = insertTerminal(connection, "013800000061", "T-TASK1-VERSION");
            actorId = insertActor(connection);
        }
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-29T12:00:00+08:00");
        OnboardSystem system = OnboardSystem.create(
                vehicleId, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY,
                actorId, createdAt);
        OnboardSystemRuntimeState runtime = OnboardSystemRuntimeState.initialize(
                system.getId(), createdAt);
        String schemaJdbcUrl = postgres.jdbcUrl() + "?currentSchema=" + schema;

        try (var context = onboardJpaContext(postgres, schema, schemaJdbcUrl)) {
            EntityManagerFactory entityManagerFactory = context.getBean(EntityManagerFactory.class);
            EntityManager entityManager = entityManagerFactory.createEntityManager();
            try {
                transact(entityManager, () -> {
                    entityManager.persist(system);
                    entityManager.persist(runtime);
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
                VersionSnapshot initial = loadVersions(entityManager, system.getId());

                transact(entityManager, () -> {
                    OnboardSystemRuntimeState persistedRuntime = entityManager.find(
                            OnboardSystemRuntimeState.class, system.getId());
                    persistedRuntime.selectLocationSource(
                            terminalId, createdAt.plusSeconds(30));
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
                VersionSnapshot afterRuntime = loadVersions(entityManager, system.getId());

                transact(entityManager, () -> {
                    OnboardSystem persistedSystem = entityManager.find(
                            OnboardSystem.class, system.getId());
                    persistedSystem.changeOperatingMode(
                            OnboardSystem.OperatingMode.DISPATCH_SERVICE,
                            actorId, createdAt.plusMinutes(1));
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
                VersionSnapshot afterConfiguration = loadVersions(
                        entityManager, system.getId());

                assertAll(
                        () -> assertThat(initial.configurationVersion()).isZero(),
                        () -> assertThat(initial.runtimeVersion()).isZero(),
                        () -> assertThat(afterRuntime.configurationVersion()).isZero(),
                        () -> assertThat(afterRuntime.runtimeVersion()).isEqualTo(1),
                        () -> assertThat(afterConfiguration.configurationVersion()).isEqualTo(1),
                        () -> assertThat(afterConfiguration.runtimeVersion()).isEqualTo(1));
            } finally {
                entityManager.close();
            }
        }
    }

    @Test
    void v19PersistsProtocolProfileCloseAndAppendWithoutRewritingProvenance() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v19_profile_history");
        flyway(postgres, schema, "19").migrate();
        UUID terminalId;
        UUID creationActorId;
        UUID changeActorId;
        try (Connection connection = connection(postgres, schema)) {
            terminalId = insertTerminal(connection, "013800000062", "T-TASK1-PROFILE-HISTORY");
            creationActorId = insertActor(connection);
            changeActorId = insertActor(connection);
        }
        OffsetDateTime validFrom = OffsetDateTime.parse("2026-08-29T12:00:00+08:00");
        OffsetDateTime validTo = validFrom.plusMinutes(30);
        OnboardDeviceProtocolProfile original = OnboardDeviceProtocolProfile.activate(
                terminalId,
                OnboardDeviceProtocolProfile.TransportProfile.JT808_2019,
                OnboardDeviceProtocolProfile.BusinessProfile.NONE,
                OnboardDeviceProtocolProfile.SafetyProfile.JSATL12_2017,
                OnboardDeviceProtocolProfile.MediaProfile.JT1078_2016,
                10, 60, "登记协议档案", creationActorId, validFrom);
        OnboardDeviceProtocolProfile replacement = OnboardDeviceProtocolProfile.activate(
                terminalId,
                OnboardDeviceProtocolProfile.TransportProfile.JT808_2019,
                OnboardDeviceProtocolProfile.BusinessProfile.NONE,
                OnboardDeviceProtocolProfile.SafetyProfile.NONE,
                OnboardDeviceProtocolProfile.MediaProfile.NONE,
                20, 120, "更新协议档案", changeActorId, validTo);
        String schemaJdbcUrl = postgres.jdbcUrl() + "?currentSchema=" + schema;

        try (var context = onboardJpaContext(postgres, schema, schemaJdbcUrl)) {
            EntityManagerFactory entityManagerFactory = context.getBean(EntityManagerFactory.class);
            EntityManager entityManager = entityManagerFactory.createEntityManager();
            try {
                transact(entityManager, () -> {
                    entityManager.persist(original);
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
                transact(entityManager, () -> {
                    OnboardDeviceProtocolProfile persistedOriginal = entityManager.find(
                            OnboardDeviceProtocolProfile.class, original.getId());
                    persistedOriginal.supersede("更新协议档案", changeActorId, validTo);
                    entityManager.flush();
                    entityManager.persist(replacement);
                    entityManager.flush();
                    entityManager.clear();
                    return null;
                });
                ProfileHistorySnapshot snapshot = transact(entityManager, () -> {
                    OnboardDeviceProtocolProfile closed = entityManager.find(
                            OnboardDeviceProtocolProfile.class, original.getId());
                    OnboardDeviceProtocolProfile active = entityManager.find(
                            OnboardDeviceProtocolProfile.class, replacement.getId());
                    ProfileHistorySnapshot value = new ProfileHistorySnapshot(
                            closed.getStatus(), closed.getValidTo(), closed.getReason(), closed.getActorId(),
                            active.getStatus(), active.getReason(), active.getActorId());
                    entityManager.clear();
                    return value;
                });

                assertAll(
                        () -> assertThat(snapshot.closedStatus())
                                .isEqualTo(OnboardDeviceProtocolProfile.Status.SUPERSEDED),
                        () -> assertThat(snapshot.closedAt()).isEqualTo(validTo),
                        () -> assertThat(snapshot.closedReason()).isEqualTo("登记协议档案"),
                        () -> assertThat(snapshot.closedActorId()).isEqualTo(creationActorId),
                        () -> assertThat(snapshot.activeStatus())
                                .isEqualTo(OnboardDeviceProtocolProfile.Status.ACTIVE),
                        () -> assertThat(snapshot.activeReason()).isEqualTo("更新协议档案"),
                        () -> assertThat(snapshot.activeActorId()).isEqualTo(changeActorId));
            } finally {
                entityManager.close();
            }
        }
    }

    private static ExternalPostgres externalPostgres() {
        Assumptions.assumeTrue(Boolean.getBoolean(INTEGRATION_PROPERTY),
                "composite onboard migration verification was not enabled");
        String jdbcUrl = System.getProperty("drt.integration.composite-onboard.jdbc-url", "");
        String username = System.getProperty("drt.integration.composite-onboard.username", "");
        String password = System.getProperty("drt.integration.composite-onboard.password", "");
        Assumptions.assumeTrue(Boolean.getBoolean("drt.integration.composite-onboard.external-ephemeral")
                        && "composite".equals(username)
                        && jdbcUrl.matches(
                                "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/composite_onboard"),
                "requires an explicit empty loopback composite_onboard PostgreSQL database");
        return new ExternalPostgres(jdbcUrl, username, password);
    }

    private static Flyway flyway(ExternalPostgres postgres, String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.jdbcUrl(), postgres.username(), postgres.password())
                .locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).createSchemas(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection connection(ExternalPostgres postgres, String schema) throws Exception {
        Connection connection = DriverManager.getConnection(
                postgres.jdbcUrl(), postgres.username(), postgres.password());
        connection.createStatement().execute("set search_path to \"" + schema + "\", public");
        return connection;
    }

    private static LegacyIds insertLegacyActiveBinding(Connection connection) throws Exception {
        UUID vehicleId = insertVehicle(connection, "TASK1-LEGACY");
        UUID terminalId = insertTerminal(connection, "013800000001", "T-TASK1-LEGACY");
        UUID bindingId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into jt_terminal_vehicle_bindings (
                  id, terminal_id, vehicle_id, valid_from, status, binding_reason
                ) values (?, ?, ?, now(), 'ACTIVE', 'Task 1 legacy backfill fixture')
                """)) {
            insert.setObject(1, bindingId);
            insert.setObject(2, terminalId);
            insert.setObject(3, vehicleId);
            insert.executeUpdate();
        }
        return new LegacyIds(vehicleId, terminalId, bindingId);
    }

    private static void insertDispatchableVehicleWithoutDispatchRole(Connection connection)
            throws Exception {
        disableSeedDispatchableVehicles(connection);
        UUID actorId = insertActor(connection);
        UUID vehicleId = insertDispatchableVehicle(connection, "TASK8-NO-DISPATCH");
        UUID systemId = insertSystem(connection, vehicleId, "DISPATCH_SERVICE");
        UUID terminalId = insertActiveTerminal(
                connection, "013800000081", "T-TASK8-NO-DISPATCH");
        insertMembership(connection, systemId, terminalId);
        insertVerifiedCapability(connection, terminalId, "JT808_LOCATION", actorId);
        insertRole(connection, systemId, terminalId, "LOCATION_PRIMARY", "ACTIVE", null);
    }

    private static void assertRoleCapabilityGate(
            String schemaSuffix,
            String terminalPhone,
            String terminalCode,
            String role,
            String wrongVerifiedCapability) throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v20_role_capability_" + schemaSuffix);
        flyway(postgres, schema, "19").migrate();
        try (Connection connection = connection(postgres, schema)) {
            disableSeedDispatchableVehicles(connection);
            UUID actorId = insertActor(connection);
            UUID systemId = insertSystem(
                    connection, insertVehicle(connection, "TASK8-CAP-" + schemaSuffix),
                    "SAFETY_MONITOR_ONLY");
            UUID terminalId = insertTerminal(connection, terminalPhone, terminalCode);
            insertMembership(connection, systemId, terminalId);
            insertVerifiedCapability(
                    connection, terminalId, wrongVerifiedCapability, actorId);
            insertRole(connection, systemId, terminalId, role, "ACTIVE", null);
        }

        assertThatThrownBy(() -> flyway(postgres, schema, "20").migrate())
                .hasStackTraceContaining("ONBOARD_CONTRACT_ROLE_CAPABILITY_MISSING");
    }

    private static ContractReadyIds insertContractReadyDualDeviceSystem(Connection connection)
            throws Exception {
        disableSeedDispatchableVehicles(connection);
        UUID actorId = insertActor(connection);
        UUID vehicleId = insertDispatchableVehicle(connection, "TASK8-CONTRACT-READY");
        UUID systemId = insertSystem(connection, vehicleId, "DISPATCH_SERVICE");
        UUID dispatchTerminalId = insertActiveTerminal(
                connection, "013800000082", "T-TASK8-DISPATCH");
        UUID locationTerminalId = insertActiveTerminal(
                connection, "013800000083", "T-TASK8-LOCATION");
        insertMembership(connection, systemId, dispatchTerminalId);
        insertMembership(connection, systemId, locationTerminalId);
        insertVerifiedCapability(
                connection, dispatchTerminalId, "GBT28787_DISPATCH", actorId);
        insertVerifiedCapability(connection, locationTerminalId, "JT808_LOCATION", actorId);
        insertRole(connection, systemId, dispatchTerminalId, "DISPATCH", "ACTIVE", null);
        insertRole(connection, systemId, locationTerminalId, "LOCATION_PRIMARY", "ACTIVE", null);
        return new ContractReadyIds(systemId, dispatchTerminalId, locationTerminalId);
    }

    private static void disableSeedDispatchableVehicles(Connection connection) throws Exception {
        execute(connection, "update vehicles set dispatchable = false where dispatchable");
    }

    private static UUID insertDispatchableVehicle(Connection connection, String plate) throws Exception {
        UUID vehicleId = insertVehicle(connection, plate);
        execute(connection, "update vehicles set dispatchable = true where id = ?", vehicleId);
        return vehicleId;
    }

    private static UUID insertActiveTerminal(Connection connection, String phone, String code)
            throws Exception {
        UUID terminalId = insertTerminal(connection, phone, code);
        execute(connection, "update jt_terminals set status = 'ACTIVE' where id = ?", terminalId);
        return terminalId;
    }

    private static UUID insertSystem(
            Connection connection, UUID vehicleId, String operatingMode) throws Exception {
        UUID id = UUID.randomUUID();
        execute(connection, """
                insert into onboard_systems (id, vehicle_id, status, operating_mode)
                values (?, ?, 'ACTIVE', ?)
                """, id, vehicleId, operatingMode);
        return id;
    }

    private static void insertVerifiedCapability(
            Connection connection, UUID terminalId, String capability, UUID actorId)
            throws Exception {
        execute(connection, """
                insert into onboard_device_capabilities (
                  id, terminal_id, capability, status, evidence_ref,
                  verified_at, verified_by, reason
                ) values (?, ?, ?, 'VERIFIED', 'task-8-controlled-evidence',
                  now(), ?, 'Task 8 verified capability fixture')
                """, UUID.randomUUID(), terminalId, capability, actorId);
    }

    private static void insertLegacyBinding(Connection connection) throws Exception {
        UUID vehicleId = insertVehicle(connection, "TASK8-LEGACY-FROZEN");
        UUID terminalId = insertTerminal(
                connection, "013800000084", "T-TASK8-LEGACY-FROZEN");
        execute(connection, """
                insert into jt_terminal_vehicle_bindings (
                  id, terminal_id, vehicle_id, valid_from, status, binding_reason
                ) values (?, ?, ?, now(), 'ACTIVE', 'Task 8 frozen legacy write fixture')
                """, UUID.randomUUID(), terminalId, vehicleId);
    }

    private static UUID insertLegacyHistory(Connection connection) throws Exception {
        UUID bindingId = UUID.randomUUID();
        UUID vehicleId = insertVehicle(connection, "TASK8-LEGACY-HISTORY");
        UUID terminalId = insertTerminal(
                connection, "013800000093", "T-TASK8-LEGACY-HISTORY");
        execute(connection, """
                insert into jt_terminal_vehicle_bindings (
                  id, terminal_id, vehicle_id, valid_from, valid_to, status,
                  binding_reason, unbinding_reason
                ) values (?, ?, ?, now() - interval '1 hour', now(), 'UNBOUND',
                  'Task 8 retained history fixture', 'Task 8 retained history close')
                """, bindingId, terminalId, vehicleId);
        return bindingId;
    }

    private static void updateLegacyBinding(Connection connection, UUID bindingId) throws Exception {
        execute(connection, """
                update jt_terminal_vehicle_bindings
                set binding_reason = 'Task 8 forbidden legacy update'
                where id = ?
                """, bindingId);
    }

    private static void deleteLegacyBinding(Connection connection, UUID bindingId) throws Exception {
        execute(connection, "delete from jt_terminal_vehicle_bindings where id = ?", bindingId);
    }

    private static UUID insertVehicle(Connection connection, String plate) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into vehicles (
                  id, plate_number, vehicle_type, capacity, current_status, fleet_name, dispatchable
                ) values (?, ?, 'BUS', 20, 'AVAILABLE', 'Task 1 migration', false)
                """)) {
            insert.setObject(1, id);
            insert.setString(2, plate);
            insert.executeUpdate();
        }
        return id;
    }

    private static UUID insertTerminal(Connection connection, String phone, String code) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into jt_terminals (
                  id, terminal_phone, terminal_phone_identity, terminal_code,
                  manufacturer_id, model, protocol_version, source_coordinate_system,
                  status, auth_token_hash, auth_token_version
                ) values (?, ?, ?, ?, 'MFG', 'MODEL', 'JT808_2019', 'GCJ02',
                  'PENDING', repeat('a', 64), 1)
                """)) {
            insert.setObject(1, id);
            insert.setString(2, phone);
            insert.setString(3, phone.length() == 20 ? phone : "0".repeat(20 - phone.length()) + phone);
            insert.setString(4, code);
            insert.executeUpdate();
        }
        return id;
    }

    private static UUID insertActor(Connection connection) throws Exception {
        UUID id = UUID.randomUUID();
        execute(connection, """
                insert into user_accounts (
                  id, username, display_name, password_hash, enabled,
                  token_version, must_change_password, created_at, updated_at
                ) values (?, ?, 'Task 1 JPA actor', 'not-used', true, 0, false, now(), now())
                """, id, "task1-jpa-" + id);
        return id;
    }

    private static void assertInvalidProtocolProfile(
            Connection connection,
            UUID terminalId,
            String transportProfile,
            String businessProfile,
            String safetyProfile,
            String mediaProfile,
            int activeIntervalSeconds,
            int idleIntervalSeconds,
            String status,
            OffsetDateTime validTo,
            String constraintName) {
        assertConstraintViolation(() -> execute(connection, """
                insert into onboard_device_protocol_profiles (
                  id, terminal_id, transport_profile, business_profile,
                  safety_profile, media_profile, active_position_interval_seconds,
                  idle_position_interval_seconds, status, valid_from, valid_to, reason
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?,
                  timestamp with time zone '2026-08-29 10:00:00+08', ?, 'invalid profile fixture')
                """, UUID.randomUUID(), terminalId, transportProfile, businessProfile,
                safetyProfile, mediaProfile, activeIntervalSeconds, idleIntervalSeconds,
                status, validTo), constraintName);
    }

    private static void insertLocationEvent(
            Connection connection, UUID vehicleId, UUID actorId, String sourceRole) throws Exception {
        execute(connection, """
                insert into vehicle_location_events (
                  id, vehicle_id, event_type, source, location, longitude, latitude,
                  coordinate_system, standardized_address, driver_reported_at, recorded_by,
                  idempotency_key, request_fingerprint, snapshot_applied,
                  outside_service_area, source_role
                ) values (?, ?, 'MANUAL_REPORTED', 'MANUAL_DISPATCHER',
                  ST_SetSRID(ST_MakePoint(121.4737, 31.2304), 4326)::geography,
                  121.4737000, 31.2304000, 'GCJ02', 'Task 1 source role fixture',
                  timestamp with time zone '2026-08-29 10:00:00+08', ?, ?,
                  repeat('b', 64), false, false, ?)
                """, UUID.randomUUID(), vehicleId, actorId, UUID.randomUUID(), sourceRole);
    }

    private static UUID insertSystem(Connection connection, UUID vehicleId) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into onboard_systems (id, vehicle_id, status, operating_mode)
                values (?, ?, 'ACTIVE', 'SAFETY_MONITOR_ONLY')
                """)) {
            insert.setObject(1, id);
            insert.setObject(2, vehicleId);
            insert.executeUpdate();
        }
        return id;
    }

    private static void insertMembership(
            Connection connection, UUID onboardSystemId, UUID terminalId) throws Exception {
        insertMembership(connection, onboardSystemId, terminalId, "DIRECT_CELLULAR");
    }

    private static void insertMembership(
            Connection connection,
            UUID onboardSystemId,
            UUID terminalId,
            String networkMode) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into onboard_device_memberships (
                  id, onboard_system_id, terminal_id, network_mode, status,
                  valid_from, added_reason
                ) values (?, ?, ?, ?, 'ACTIVE', now(), 'Task 1 membership fixture')
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, onboardSystemId);
            insert.setObject(3, terminalId);
            insert.setString(4, networkMode);
            insert.executeUpdate();
        }
    }

    private static void insertCapability(
            Connection connection, UUID terminalId, String capability, String status) throws Exception {
        execute(connection, """
                insert into onboard_device_capabilities (
                  id, terminal_id, capability, status, evidence_ref,
                  verified_at, reason
                ) values (?, ?, ?, ?, 'task-1-fixture',
                  case when ? = 'VERIFIED' then now() else null end,
                  'Task 1 capability fixture')
                """, UUID.randomUUID(), terminalId, capability, status, status);
    }

    private static void insertProtocolProfile(
            Connection connection,
            UUID terminalId,
            String status,
            java.time.OffsetDateTime validTo,
            int activeIntervalSeconds,
            int idleIntervalSeconds) throws Exception {
        execute(connection, """
                insert into onboard_device_protocol_profiles (
                  id, terminal_id, transport_profile, business_profile,
                  safety_profile, media_profile, active_position_interval_seconds,
                  idle_position_interval_seconds, status, valid_from, valid_to, reason
                ) values (?, ?, 'JT808_2019', 'NONE', 'NONE', 'NONE', ?, ?, ?,
                  now(), ?, 'Task 1 protocol profile fixture')
                """, UUID.randomUUID(), terminalId, activeIntervalSeconds, idleIntervalSeconds,
                status, validTo);
    }

    private static void insertRole(
            Connection connection,
            UUID onboardSystemId,
            UUID terminalId,
            String role,
            String status,
            java.time.OffsetDateTime validTo) throws Exception {
        execute(connection, """
                insert into onboard_device_role_assignments (
                  id, onboard_system_id, terminal_id, role, status,
                  valid_from, valid_to, assigned_reason
                ) values (?, ?, ?, ?, ?, now(), ?, 'Task 1 role fixture')
                """, UUID.randomUUID(), onboardSystemId, terminalId, role, status, validTo);
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }

    private static void assertConstraintViolation(ThrowingCallable action, String constraintName) {
        assertThatThrownBy(action).hasStackTraceContaining(constraintName);
    }

    private static void assertInvalidWarningCodes(
            Connection connection, UUID onboardSystemId, String warningCodesJson) {
        assertConstraintViolation(() -> execute(connection, """
                insert into onboard_system_runtime_state (
                  onboard_system_id, primary_recovery_streak, primary_eligible, warning_codes
                ) values (?, 0, true, cast(? as jsonb))
                """, onboardSystemId, warningCodesJson),
                "ck_onboard_system_runtime_state_warning_codes_content");
    }

    private static List<String> columnNames(Connection connection, String tableName) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("""
                select column_name
                from information_schema.columns
                where table_schema = current_schema() and table_name = ?
                order by ordinal_position
                """)) {
            query.setString(1, tableName);
            try (var rows = query.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
                return names;
            }
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws Exception {
        return queryCount(connection, """
                select count(*)
                from information_schema.tables
                where table_schema = current_schema() and table_name = ?
                """, tableName) == 1;
    }

    private static boolean indexExists(Connection connection, String indexName) throws Exception {
        return queryCount(connection, """
                select count(*)
                from pg_indexes
                where schemaname = current_schema() and indexname = ?
                """, indexName) == 1;
    }

    private static int queryCount(Connection connection, String sql, Object... arguments) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                query.setObject(index + 1, arguments[index]);
            }
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    private static ConfigurableApplicationContext onboardJpaContext(
            ExternalPostgres postgres, String schema, String schemaJdbcUrl) {
        return new SpringApplicationBuilder(OnboardJpaTestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + schemaJdbcUrl,
                        "spring.datasource.username=" + postgres.username(),
                        "spring.datasource.password=" + postgres.password(),
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.datasource.hikari.maximum-pool-size=2",
                        "spring.flyway.enabled=false",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "spring.jpa.open-in-view=false",
                        "spring.jpa.properties.hibernate.default_schema=" + schema,
                        "spring.main.banner-mode=off")
                .run();
    }

    private static <T> T transact(EntityManager entityManager, Supplier<T> action) {
        var transaction = entityManager.getTransaction();
        transaction.begin();
        try {
            T value = action.get();
            transaction.commit();
            return value;
        } catch (RuntimeException | Error failure) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw failure;
        }
    }

    private static VersionSnapshot loadVersions(EntityManager entityManager, UUID onboardSystemId) {
        return transact(entityManager, () -> {
            OnboardSystem system = entityManager.find(OnboardSystem.class, onboardSystemId);
            OnboardSystemRuntimeState runtime = entityManager.find(
                    OnboardSystemRuntimeState.class, onboardSystemId);
            VersionSnapshot snapshot = new VersionSnapshot(
                    system.getVersion(), runtime.getRuntimeVersion());
            entityManager.clear();
            return snapshot;
        });
    }

    private static void assertMembershipFailureAtomic(
            Consumer<OnboardDeviceMembership> action, OffsetDateTime validFrom) {
        OnboardDeviceMembership membership = OnboardDeviceMembership.join(
                UUID.randomUUID(), UUID.randomUUID(),
                OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                "安装设备", UUID.randomUUID(), validFrom);
        MembershipSnapshot before = snapshot(membership);
        assertThatThrownBy(() -> action.accept(membership)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot(membership)).isEqualTo(before);
    }

    private static void assertCapabilityFailureAtomic(
            Consumer<OnboardDeviceCapability> action, OffsetDateTime declaredAt) {
        OnboardDeviceCapability capability = OnboardDeviceCapability.declare(
                UUID.randomUUID(), OnboardDeviceCapability.Capability.JT808_LOCATION,
                "声明能力", declaredAt);
        CapabilitySnapshot before = snapshot(capability);
        assertThatThrownBy(() -> action.accept(capability)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot(capability)).isEqualTo(before);
    }

    private static void assertRoleFailureAtomic(
            Consumer<OnboardDeviceRoleAssignment> action, OffsetDateTime validFrom) {
        OnboardDeviceRoleAssignment role = OnboardDeviceRoleAssignment.assign(
                UUID.randomUUID(), UUID.randomUUID(),
                OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                "分配角色", UUID.randomUUID(), validFrom);
        RoleAssignmentSnapshot before = snapshot(role);
        assertThatThrownBy(() -> action.accept(role)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot(role)).isEqualTo(before);
    }

    private static OnboardDeviceProtocolProfile protocolProfile(
            UUID terminalId, String reason, UUID actorId, OffsetDateTime validFrom) {
        return OnboardDeviceProtocolProfile.activate(
                terminalId,
                OnboardDeviceProtocolProfile.TransportProfile.JT808_2019,
                OnboardDeviceProtocolProfile.BusinessProfile.NONE,
                OnboardDeviceProtocolProfile.SafetyProfile.NONE,
                OnboardDeviceProtocolProfile.MediaProfile.NONE,
                10, 60, reason, actorId, validFrom);
    }

    private static void assertMembershipTextFailureAtomic(
            String tooLongReason, OffsetDateTime validFrom, OffsetDateTime changedAt) {
        OnboardDeviceMembership membership = OnboardDeviceMembership.join(
                UUID.randomUUID(), UUID.randomUUID(),
                OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                "安装", UUID.randomUUID(), validFrom);
        MembershipSnapshot before = snapshot(membership);
        assertThatThrownBy(() -> membership.remove(
                tooLongReason, UUID.randomUUID(), changedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshot(membership)).isEqualTo(before);
    }

    private static void assertCapabilityVerifyTextFailureAtomic(
            String evidenceRef,
            String reason,
            OffsetDateTime declaredAt,
            OffsetDateTime changedAt) {
        OnboardDeviceCapability capability = OnboardDeviceCapability.declare(
                UUID.randomUUID(), OnboardDeviceCapability.Capability.JT808_LOCATION,
                "声明", declaredAt);
        CapabilitySnapshot before = snapshot(capability);
        assertThatThrownBy(() -> capability.verify(
                evidenceRef, UUID.randomUUID(), reason, changedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshot(capability)).isEqualTo(before);
    }

    private static void assertCapabilityDisableTextFailureAtomic(
            String reason, OffsetDateTime declaredAt, OffsetDateTime changedAt) {
        OnboardDeviceCapability capability = OnboardDeviceCapability.declare(
                UUID.randomUUID(), OnboardDeviceCapability.Capability.JT808_LOCATION,
                "声明", declaredAt);
        CapabilitySnapshot before = snapshot(capability);
        assertThatThrownBy(() -> capability.disable(reason, changedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshot(capability)).isEqualTo(before);
    }

    private static void assertProfileTextFailureAtomic(
            String reason, OffsetDateTime validFrom, OffsetDateTime changedAt) {
        OnboardDeviceProtocolProfile profile = protocolProfile(
                UUID.randomUUID(), "登记", UUID.randomUUID(), validFrom);
        ProtocolProfileSnapshot before = snapshot(profile);
        assertThatThrownBy(() -> profile.supersede(reason, UUID.randomUUID(), changedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshot(profile)).isEqualTo(before);
    }

    private static void assertRoleTextFailureAtomic(
            String reason, OffsetDateTime validFrom, OffsetDateTime changedAt) {
        OnboardDeviceRoleAssignment role = OnboardDeviceRoleAssignment.assign(
                UUID.randomUUID(), UUID.randomUUID(),
                OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                "分配", UUID.randomUUID(), validFrom);
        RoleAssignmentSnapshot before = snapshot(role);
        assertThatThrownBy(() -> role.revoke(reason, UUID.randomUUID(), changedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshot(role)).isEqualTo(before);
    }

    private static OnboardSystemSnapshot snapshot(OnboardSystem system) {
        return new OnboardSystemSnapshot(
                system.getId(), system.getVehicleId(), system.getStatus(), system.getOperatingMode(),
                system.getCreatedBy(), system.getUpdatedBy(), system.getCreatedAt(),
                system.getUpdatedAt(), system.getVersion());
    }

    private static RuntimeStateSnapshot snapshot(OnboardSystemRuntimeState runtime) {
        return new RuntimeStateSnapshot(
                runtime.getOnboardSystemId(), runtime.getActiveLocationTerminalId(),
                runtime.getPrimaryRecoveryStreak(), runtime.isPrimaryEligible(),
                runtime.getLastPrimaryValidAt(), runtime.getLastLocationSwitchAt(),
                runtime.getWarningCodes(), runtime.getUpdatedAt(), runtime.getRuntimeVersion());
    }

    private static MembershipSnapshot snapshot(OnboardDeviceMembership membership) {
        return new MembershipSnapshot(
                membership.getId(), membership.getOnboardSystemId(), membership.getTerminalId(),
                membership.getNetworkMode(), membership.getStatus(), membership.getValidFrom(),
                membership.getValidTo(), membership.getAddedReason(), membership.getRemovedReason(),
                membership.getAddedBy(), membership.getRemovedBy(), membership.getCreatedAt(),
                membership.getUpdatedAt(), membership.getVersion());
    }

    private static CapabilitySnapshot snapshot(OnboardDeviceCapability capability) {
        return new CapabilitySnapshot(
                capability.getId(), capability.getTerminalId(), capability.getCapability(),
                capability.getStatus(), capability.getEvidenceRef(), capability.getVerifiedAt(),
                capability.getVerifiedBy(), capability.getReason(), capability.getCreatedAt(),
                capability.getUpdatedAt(), capability.getVersion());
    }

    private static ProtocolProfileSnapshot snapshot(OnboardDeviceProtocolProfile profile) {
        return new ProtocolProfileSnapshot(
                profile.getId(), profile.getTerminalId(), profile.getTransportProfile(),
                profile.getBusinessProfile(), profile.getSafetyProfile(), profile.getMediaProfile(),
                profile.getActivePositionIntervalSeconds(), profile.getIdlePositionIntervalSeconds(),
                profile.getStatus(), profile.getValidFrom(), profile.getValidTo(), profile.getReason(),
                profile.getActorId(), profile.getCreatedAt(), profile.getUpdatedAt(), profile.getVersion());
    }

    private static RoleAssignmentSnapshot snapshot(OnboardDeviceRoleAssignment role) {
        return new RoleAssignmentSnapshot(
                role.getId(), role.getOnboardSystemId(), role.getTerminalId(), role.getRole(),
                role.getStatus(), role.getValidFrom(), role.getValidTo(), role.getAssignedReason(),
                role.getRevokedReason(), role.getAssignedBy(), role.getRevokedBy(), role.getCreatedAt(),
                role.getUpdatedAt(), role.getVersion());
    }

    private static String schema(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private record ExternalPostgres(String jdbcUrl, String username, String password) {
    }

    private record LegacyIds(UUID vehicleId, UUID terminalId, UUID bindingId) {
    }

    private record ContractReadyIds(
            UUID systemId, UUID dispatchTerminalId, UUID locationTerminalId) {
    }

    private record VersionSnapshot(long configurationVersion, long runtimeVersion) {
    }

    private record ProfileHistorySnapshot(
            OnboardDeviceProtocolProfile.Status closedStatus,
            OffsetDateTime closedAt,
            String closedReason,
            UUID closedActorId,
            OnboardDeviceProtocolProfile.Status activeStatus,
            String activeReason,
            UUID activeActorId) {
    }

    private record OnboardSystemSnapshot(
            UUID id,
            UUID vehicleId,
            OnboardSystem.Status status,
            OnboardSystem.OperatingMode operatingMode,
            UUID createdBy,
            UUID updatedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long version) {
    }

    private record RuntimeStateSnapshot(
            UUID onboardSystemId,
            UUID activeLocationTerminalId,
            int primaryRecoveryStreak,
            boolean primaryEligible,
            OffsetDateTime lastPrimaryValidAt,
            OffsetDateTime lastLocationSwitchAt,
            List<String> warningCodes,
            OffsetDateTime updatedAt,
            long runtimeVersion) {
        private RuntimeStateSnapshot {
            warningCodes = List.copyOf(warningCodes);
        }
    }

    private record MembershipSnapshot(
            UUID id,
            UUID onboardSystemId,
            UUID terminalId,
            OnboardDeviceMembership.NetworkMode networkMode,
            OnboardDeviceMembership.Status status,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            String addedReason,
            String removedReason,
            UUID addedBy,
            UUID removedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long version) {
    }

    private record CapabilitySnapshot(
            UUID id,
            UUID terminalId,
            OnboardDeviceCapability.Capability capability,
            OnboardDeviceCapability.CapabilityStatus status,
            String evidenceRef,
            OffsetDateTime verifiedAt,
            UUID verifiedBy,
            String reason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long version) {
    }

    private record ProtocolProfileSnapshot(
            UUID id,
            UUID terminalId,
            OnboardDeviceProtocolProfile.TransportProfile transportProfile,
            OnboardDeviceProtocolProfile.BusinessProfile businessProfile,
            OnboardDeviceProtocolProfile.SafetyProfile safetyProfile,
            OnboardDeviceProtocolProfile.MediaProfile mediaProfile,
            int activePositionIntervalSeconds,
            int idlePositionIntervalSeconds,
            OnboardDeviceProtocolProfile.Status status,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            String reason,
            UUID actorId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long version) {
    }

    private record RoleAssignmentSnapshot(
            UUID id,
            UUID onboardSystemId,
            UUID terminalId,
            OnboardDeviceRoleAssignment.Role role,
            OnboardDeviceRoleAssignment.Status status,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            String assignedReason,
            String revokedReason,
            UUID assignedBy,
            UUID revokedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long version) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = OnboardSystem.class)
    @EnableJpaRepositories(basePackageClasses = OnboardSystemRepository.class)
    static class OnboardJpaTestConfiguration {
    }
}
