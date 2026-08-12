package com.idavy.drtops.jtgateway.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class GatewayIngressBufferTest {
    private static final Instant NOW = Instant.parse("2026-08-12T04:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAcrossRestartAndDeduplicatesByIdempotencyKey() {
        String url = fileDatabaseUrl(temporaryDirectory.resolve("restart-buffer"));
        DataSource firstDataSource = dataSource(url);
        migrate(firstDataSource);
        UUID key = UUID.fromString("10000000-0000-0000-0000-000000000001");
        GatewayIngressEnvelope envelope = envelope(key, IngressKind.LOCATION, "{\"speedKph\":32}");
        GatewayIngressBuffer first = buffer(firstDataSource);

        assertEquals(GatewayIngressBuffer.WriteResult.STORED, first.append(envelope));
        assertEquals(GatewayIngressBuffer.WriteResult.DUPLICATE, first.append(envelope));

        DataSource restartedDataSource = dataSource(url);
        GatewayOutboxRepository restartedRepository = new GatewayOutboxRepository(restartedDataSource);
        GatewayIngressBuffer restarted = new GatewayIngressBuffer(
                restartedRepository, new ObjectMapper(), fixedClock());
        GatewayOutboxRepository.OutboxEntry recovered =
                restartedRepository.find(key).orElseThrow();
        assertEquals(GatewayOutboxRepository.DeliveryStatus.PENDING, recovered.status());
        assertEquals("{\"speedKph\":32}", recovered.payloadJson());
        assertEquals(1, restartedRepository.totalCount());
        assertTrue(restarted.bufferWritable());
    }

    @Test
    void recoversInterruptedDeliveriesWhenGatewayRestarts() {
        DataSource dataSource = migratedMemoryDataSource();
        GatewayOutboxRepository repository = new GatewayOutboxRepository(dataSource);
        GatewayIngressBuffer first = new GatewayIngressBuffer(
                repository, new ObjectMapper(), fixedClock());
        UUID key = UUID.fromString("10000000-0000-0000-0000-000000000002");
        first.append(envelope(key, IngressKind.ALARM, "{\"alarmType\":\"ADAS\"}"));
        assertEquals(1, repository.claimEligible(
                NOW, GatewayOutboxRepository.Priority.HIGH, 10).size());
        assertEquals(GatewayOutboxRepository.DeliveryStatus.DELIVERING,
                repository.find(key).orElseThrow().status());

        new GatewayIngressBuffer(repository, new ObjectMapper(), fixedClock());

        assertEquals(GatewayOutboxRepository.DeliveryStatus.PENDING,
                repository.find(key).orElseThrow().status());
    }

    @Test
    void rejectsPayloadsContainingCredentialsOrAttachmentUrlsBeforePersistence() {
        DataSource dataSource = migratedMemoryDataSource();
        GatewayOutboxRepository repository = new GatewayOutboxRepository(dataSource);
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(
                repository, new ObjectMapper(), fixedClock());

        assertThrows(IllegalArgumentException.class, () -> buffer.append(envelope(
                UUID.fromString("10000000-0000-0000-0000-000000000003"),
                IngressKind.ALARM,
                "{\"authenticationToken\":\"must-not-persist\"}")));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(envelope(
                UUID.fromString("10000000-0000-0000-0000-000000000004"),
                IngressKind.ATTACHMENT_CONTROL,
                "{\"attachmentUrl\":\"https://media.invalid/file\"}")));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(envelope(
                UUID.fromString("10000000-0000-0000-0000-000000000006"),
                IngressKind.ALARM,
                "{\"authentication_code\":\"must-not-persist\"}")));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(envelope(
                UUID.fromString("10000000-0000-0000-0000-000000000007"),
                IngressKind.ATTACHMENT_CONTROL,
                "{\"upload-token\":\"must-not-persist\"}")));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(envelope(
                UUID.fromString("10000000-0000-0000-0000-000000000008"),
                IngressKind.ATTACHMENT_CONTROL,
                "{\"attachment_url\":\"https://media.invalid/file\"}")));
        assertEquals(0, repository.totalCount());
    }

    @Test
    void reportsBufferUnavailableWithoutAcknowledgingPersistence() {
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(
                new GatewayOutboxRepository(new AlwaysFailingDataSource()),
                new ObjectMapper(), fixedClock());

        assertEquals(GatewayIngressBuffer.WriteResult.UNAVAILABLE, buffer.append(envelope(
                UUID.fromString("10000000-0000-0000-0000-000000000005"),
                IngressKind.LOCATION,
                "{\"speedKph\":0}")));
        assertFalse(buffer.bufferWritable());
        assertFalse(buffer.mayAcknowledgeSuccessfulPersistence());
    }

    private GatewayIngressBuffer buffer(DataSource dataSource) {
        return new GatewayIngressBuffer(
                new GatewayOutboxRepository(dataSource), new ObjectMapper(), fixedClock());
    }

    private DataSource migratedMemoryDataSource() {
        DataSource dataSource = dataSource(
                "jdbc:h2:mem:gateway_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        migrate(dataSource);
        return dataSource;
    }

    private static GatewayIngressEnvelope envelope(
            UUID key, IngressKind kind, String payloadJson) {
        return new GatewayIngressEnvelope(1, key, kind, NOW, payloadJson);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static DataSource dataSource(String url) {
        return new DriverManagerDataSource(url, "sa", "");
    }

    private static String fileDatabaseUrl(Path path) {
        return "jdbc:h2:file:" + path.toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL";
    }

    private static void migrate(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    private static final class AlwaysFailingDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("synthetic buffer outage");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("synthetic buffer outage");
        }

        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("unsupported"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }
}
