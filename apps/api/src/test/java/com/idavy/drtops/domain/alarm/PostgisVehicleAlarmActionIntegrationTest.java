package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@EnabledIf("integrationEnvironmentAvailable")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.jpa.open-in-view=false"})
@Import(PostgisVehicleAlarmActionIntegrationTest.AuthorizationConfiguration.class)
class PostgisVehicleAlarmActionIntegrationTest {
    private static PostgreSQLContainer<?> postgres;

    @Autowired VehicleAlarmActionService service;
    @Autowired JdbcTemplate jdbc;

    private Fixture fixture;

    @DynamicPropertySource
    static void postgisProperties(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> container = postgres();
        container.start();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    @BeforeEach
    void setUp() {
        removeOutboxRejector();
        fixture = fixture();
    }

    @AfterEach
    void tearDown() {
        removeOutboxRejector();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void persistsControlledActionAuditAndOutboxWithJpaMappingsAgainstPostgresql() {
        VehicleAlarm changed = service.transition(
                fixture.alarmId(), 0, fixture.actorId(), VehicleAlarm.ProcessingStatus.ACKNOWLEDGED, "现场已确认");

        assertThat(changed.getVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select processing_status from vehicle_alarms where id = ?", String.class, fixture.alarmId()))
                .isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.queryForObject("select version from vehicle_alarms where id = ?", Long.class, fixture.alarmId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from vehicle_alarm_actions where vehicle_alarm_id = ?", Integer.class,
                fixture.alarmId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where entity_id = ?", Integer.class,
                fixture.alarmId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vehicle_alarm_outbox where vehicle_alarm_id = ?", Integer.class,
                fixture.alarmId())).isEqualTo(1);
    }

    @Test
    void rollsBackTheStatusActionAndAuditWhenTheOutboxWriteIsRejected() {
        jdbc.execute("""
                create function reject_alarm_action_outbox_for_test() returns trigger language plpgsql as $$
                begin
                  if new.event_type = 'ALARM_STATUS_CHANGED' then
                    raise exception 'alarm action outbox test failure';
                  end if;
                  return new;
                end;
                $$;
                create trigger trg_reject_alarm_action_outbox_for_test before insert on vehicle_alarm_outbox
                for each row execute function reject_alarm_action_outbox_for_test();
                """);

        assertThatThrownBy(() -> service.transition(
                fixture.alarmId(), 0, fixture.actorId(), VehicleAlarm.ProcessingStatus.ACKNOWLEDGED, "现场已确认"))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "select processing_status from vehicle_alarms where id = ?", String.class, fixture.alarmId()))
                .isEqualTo("NEW");
        assertThat(jdbc.queryForObject("select version from vehicle_alarms where id = ?", Long.class, fixture.alarmId()))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from vehicle_alarm_actions where vehicle_alarm_id = ?", Integer.class,
                fixture.alarmId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where entity_id = ?", Integer.class,
                fixture.alarmId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from vehicle_alarm_outbox where vehicle_alarm_id = ?", Integer.class,
                fixture.alarmId())).isZero();
    }

    private Fixture fixture() {
        UUID terminalId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID alarmId = UUID.randomUUID();
        UUID vehicleId = jdbc.queryForObject("select id from vehicles order by id limit 1", UUID.class);
        String compactTerminalId = terminalId.toString().replace("-", "");
        jdbc.update("""
                insert into jt_terminals (id, terminal_phone, terminal_code, manufacturer_id, model, protocol_version,
                  source_coordinate_system, status, auth_token_hash, auth_token_version)
                values (?, ?, ?, 'TEST', 'TEST', 'JT808-2019', 'GCJ02', 'ACTIVE', repeat('a', 64), 1)
                """, terminalId, "ACTION-" + compactTerminalId.substring(0, 20), "ACTION-CODE-" + compactTerminalId);
        jdbc.update("""
                insert into user_accounts (
                  id, username, display_name, password_hash, enabled, token_version, must_change_password, created_at, updated_at)
                values (?, ?, '报警处置测试员', 'not-used', true, 0, false, now(), now())
                """, actorId, "alarm-action-" + compactTerminalId.substring(0, 20));
        jdbc.update("""
                insert into vehicle_alarms (
                  id, vehicle_id, terminal_id, standard, module, terminal_alarm_id,
                  alarm_type_code, alarm_type_name_snapshot, alarm_level, terminal_alarm_identifier,
                  terminal_alarm_state, occurred_at, gateway_received_at, longitude, latitude, speed_kph,
                  location_quality_status, location_quality_reasons, processing_status, payload_digest,
                  deduplication_key, version, created_at)
                values (?, ?, ?, 'T/JSATL12-2017', 'ADAS', 4097, 1, 'FORWARD_COLLISION', 1, '00000001',
                  'START', ?, ?, 118.0000000, 32.0000000, 60.00, 'GOOD', '[]'::jsonb, 'NEW', repeat('a', 64),
                  ?, 0, now())
                """, alarmId, vehicleId, terminalId,
                Instant.parse("2026-01-15T02:00:00Z").atOffset(java.time.ZoneOffset.UTC),
                Instant.parse("2026-01-15T02:00:01Z").atOffset(java.time.ZoneOffset.UTC),
                compactTerminalId + UUID.randomUUID().toString().replace("-", ""));
        return new Fixture(terminalId, actorId, alarmId);
    }

    private void removeOutboxRejector() {
        jdbc.execute("drop trigger if exists trg_reject_alarm_action_outbox_for_test on vehicle_alarm_outbox");
        jdbc.execute("drop function if exists reject_alarm_action_outbox_for_test()");
    }

    static boolean integrationEnvironmentAvailable() {
        try {
            return Boolean.getBoolean("drt.integration.postgis") && DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres"))
                    .withStartupTimeout(Duration.ofMinutes(5));
        }
        return postgres;
    }

    @TestConfiguration
    static class AuthorizationConfiguration {
        @Bean
        @Primary
        VehicleAlarmAuthorization vehicleAlarmAuthorization() {
            return new VehicleAlarmAuthorization() {
                @Override public boolean mayHandle(UUID actorId) { return true; }
                @Override public boolean mayReopen(UUID actorId) { return true; }
            };
        }
    }

    private record Fixture(UUID terminalId, UUID actorId, UUID alarmId) { }
}
