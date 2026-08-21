package com.idavy.drtops.jtgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.core.Jt808CoreModule;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jtgateway.dispatch.ProtocolModuleRegistry;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxDispatcher;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxRepository;
import com.idavy.drtops.jtgateway.ingress.OperationsApiClient;
import com.idavy.drtops.jtgateway.ingress.OperationsApiHealthProbe;
import com.idavy.drtops.jtgateway.ingress.OperationsApiStatus;
import com.idavy.drtops.jtgateway.netty.JtGatewayServer;
import com.idavy.drtops.jtgateway.session.OperationsTerminalRegistryClient;
import com.idavy.drtops.jtgateway.session.TerminalRegistryPort;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class JtGatewayRuntimeConfiguration {

    @Bean
    Clock gatewayClock() {
        return Clock.systemUTC();
    }

    @Bean
    GatewayOutboxRepository gatewayOutboxRepository(DataSource dataSource) {
        return new GatewayOutboxRepository(dataSource);
    }

    @Bean
    @DependsOnDatabaseInitialization
    GatewayIngressBuffer gatewayIngressBuffer(
            GatewayOutboxRepository repository, ObjectMapper objectMapper, Clock gatewayClock) {
        return new GatewayIngressBuffer(repository, objectMapper, gatewayClock);
    }

    @Bean
    OperationsApiStatus operationsApiStatus(Clock gatewayClock, Environment environment) {
        return new OperationsApiStatus(gatewayClock, Duration.ofSeconds(integer(
                environment, "jt.gateway.health.api-status-ttl-seconds", 90)));
    }

    @Bean
    Jt808CoreModule jt808CoreModule() {
        return new Jt808CoreModule(new LocationReportCodec());
    }

    @Bean
    ProtocolModuleRegistry protocolModuleRegistry(
            Jt808CoreModule coreModule,
            GatewayIngressBuffer buffer,
            ObjectMapper objectMapper,
            TerminalSessionRegistry sessions,
            Clock gatewayClock) {
        return new ProtocolModuleRegistry(coreModule, buffer, objectMapper, sessions, gatewayClock);
    }

    @Bean
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    ServiceConfiguration gatewayServiceConfiguration(Environment environment) {
        return ServiceConfiguration.from(environment);
    }

    @Bean
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    HttpTimeoutConfiguration gatewayHttpTimeoutConfiguration(Environment environment) {
        return HttpTimeoutConfiguration.from(environment);
    }

    @Bean
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    TerminalRegistryPort terminalRegistryPort(
            RestClient.Builder builder,
            ServiceConfiguration service,
            HttpTimeoutConfiguration timeouts,
            OperationsApiStatus apiStatus,
            GatewayIngressBuffer auditBuffer,
            ObjectMapper objectMapper) {
        return new OperationsTerminalRegistryClient(
                boundedBuilder(builder, timeouts), service.baseUrl().toString(), service.credential(),
                service.credentialVersion(), service.gatewayInstance(), new SecureRandom(), apiStatus,
                auditBuffer, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    OperationsApiClient operationsApiClient(
            RestClient.Builder builder,
            ServiceConfiguration service,
            HttpTimeoutConfiguration timeouts,
            OperationsApiStatus apiStatus) {
        return new OperationsApiClient(
                boundedBuilder(builder, timeouts),
                endpoint(service.baseUrl(), "/internal/jt-gateway/ingress"),
                endpoint(service.baseUrl(), "/internal/jt-gateway/audit-events"),
                service::credential,
                service.credentialVersion(),
                apiStatus);
    }

    @Bean
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    OperationsApiHealthProbe operationsApiHealthProbe(
            RestClient.Builder builder,
            ServiceConfiguration service,
            HttpTimeoutConfiguration timeouts,
            OperationsApiStatus apiStatus) {
        return new OperationsApiHealthProbe(
                boundedBuilder(builder, timeouts),
                endpoint(service.baseUrl(), "/actuator/health"),
                apiStatus);
    }

    @Bean(name = "gatewayDispatchTaskScheduler")
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    ThreadPoolTaskScheduler gatewayDispatchTaskScheduler() {
        return taskScheduler("jt-gateway-dispatch-");
    }

    @Bean(name = "gatewayProbeTaskScheduler")
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    ThreadPoolTaskScheduler gatewayProbeTaskScheduler() {
        return taskScheduler("jt-gateway-probe-");
    }

    @Bean
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    GatewayOutboxDispatcher gatewayOutboxDispatcher(
            GatewayOutboxRepository repository,
            OperationsApiClient client,
            Clock gatewayClock,
            Environment environment) {
        return new GatewayOutboxDispatcher(
                repository,
                client,
                gatewayClock,
                integer(environment, "jt.gateway.dispatch.max-attempts", 8),
                Duration.ofMillis(integer(environment, "jt.gateway.dispatch.initial-backoff-ms", 1000)),
                Duration.ofMillis(integer(environment, "jt.gateway.dispatch.maximum-backoff-ms", 300000)));
    }

    @Bean
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    GatewayDispatchScheduler gatewayDispatchScheduler(GatewayOutboxDispatcher dispatcher) {
        return new GatewayDispatchScheduler(dispatcher);
    }

    @Bean
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    JtGatewayServer jtGatewayServer(
            TerminalRegistryPort registryPort,
            TerminalSessionRegistry sessions,
            ProtocolModuleRegistry protocolModules,
            Environment environment) {
        try {
            InetAddress address = InetAddress.getByName(
                    environment.getProperty("jt.gateway.tcp.bind-address", "0.0.0.0"));
            JtGatewayServer.Configuration configuration = new JtGatewayServer.Configuration(
                    new InetSocketAddress(address, integer(environment, "jt.gateway.tcp.port", 7611)),
                    integer(environment, "jt.gateway.tcp.max-connections-per-ip", 4),
                    integer(environment, "jt.gateway.tcp.max-messages-per-second", 100),
                    integer(environment, "jt.gateway.tcp.business-threads", 2),
                    integer(environment, "jt.gateway.tcp.maximum-pending-business-tasks", 1024),
                    integer(environment, "jt.gateway.tcp.business-queue-high-watermark", 800),
                    integer(environment, "jt.gateway.tcp.business-queue-low-watermark", 400),
                    Duration.ofMillis(integer(
                            environment, "jt.gateway.tcp.maximum-congestion-ms", 5000)));
            return new JtGatewayServer(configuration, registryPort, sessions, protocolModules);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("JT gateway bind address is invalid", exception);
        }
    }

    @Bean
    @ConditionalOnProperty(name = "jt.gateway.tcp.enabled", havingValue = "true")
    GatewayServerLifecycle gatewayServerLifecycle(JtGatewayServer server) {
        return new GatewayServerLifecycle(server);
    }

    @Bean
    HealthIndicator jtGatewayLivenessHealthIndicator(
            ObjectProvider<GatewayServerLifecycle> lifecycle,
            Environment environment) {
        return () -> {
            boolean enabled = environment.getProperty(
                    "jt.gateway.tcp.enabled", Boolean.class, false);
            GatewayServerLifecycle listener = lifecycle.getIfAvailable();
            boolean listening = !enabled || listener != null && listener.isListening();
            return (listening ? Health.up() : Health.down())
                    .withDetail("tcpListening", enabled && listening)
                    .build();
        };
    }

    @Bean
    HealthIndicator jtGatewayHealthIndicator(
            GatewayIngressBuffer buffer,
            GatewayOutboxRepository repository,
            ObjectProvider<GatewayServerLifecycle> lifecycle,
            ObjectProvider<OperationsApiClient> apiClient,
            OperationsApiStatus apiStatus,
            Clock gatewayClock,
            Environment environment) {
        return () -> {
            boolean enabled = environment.getProperty(
                    "jt.gateway.tcp.enabled", Boolean.class, false);
            GatewayServerLifecycle listener = lifecycle.getIfAvailable();
            OperationsApiClient operations = apiClient.getIfAvailable();
            boolean tcpListening = enabled && listener != null && listener.isListening();
            boolean writable = buffer.bufferWritable();
            GatewayOutboxRepository.OperationalSnapshot outbox =
                    new GatewayOutboxRepository.OperationalSnapshot(-1, -1, -1, -1);
            if (writable) {
                try {
                    outbox = repository.operationalSnapshot(gatewayClock.instant());
                } catch (DataAccessException unavailable) {
                    writable = false;
                }
            }
            OperationsApiStatus.Snapshot api = apiStatus.snapshot();
            Object apiState = enabled ? api.state().name() : "DISABLED";
            Object reachable = !enabled ? "DISABLED" : switch (api.state()) {
                case UNKNOWN -> "UNKNOWN";
                case UP -> true;
                case DOWN -> false;
            };
            Object lastDeliverySuccessful = !enabled ? "DISABLED"
                    : operations == null || !operations.deliveryAttempted() ? "UNKNOWN"
                    : operations.lastDeliverySuccessful();
            int maximumAge = integer(environment, "jt.gateway.health.max-unresolved-age-seconds", 300);
            boolean up = writable
                    && (!enabled || tcpListening && api.state() == OperationsApiStatus.State.UP)
                    && outbox.deadLetter() == 0
                    && outbox.oldestUnresolvedAgeSeconds() <= maximumAge;
            Health.Builder health = up ? Health.up() : Health.down();
            return health
                    .withDetail("tcpListening", tcpListening)
                    .withDetail("bufferWritable", writable)
                    .withDetail("operationsApiStatus", apiState)
                    .withDetail("operationsApiOperation", api.operation())
                    .withDetail("operationsApiReachable", reachable)
                    .withDetail("operationsApiRegistryStatus",
                            sourceHealthState(enabled, api, OperationsApiStatus.Source.REGISTRY))
                    .withDetail("operationsApiIngressStatus",
                            sourceHealthState(enabled, api, OperationsApiStatus.Source.INGRESS))
                    .withDetail("operationsApiProbeStatus",
                            sourceHealthState(enabled, api, OperationsApiStatus.Source.PROBE))
                    .withDetail("lastDeliverySuccessful", lastDeliverySuccessful)
                    .withDetail("outboxPending", outbox.pending())
                    .withDetail("outboxDelivering", outbox.delivering())
                    .withDetail("outboxDeadLetter", outbox.deadLetter())
                    .withDetail("oldestUnresolvedAgeSeconds", outbox.oldestUnresolvedAgeSeconds())
                    .build();
        };
    }

    @Bean
    MeterBinder jtGatewayMetrics(
            GatewayIngressBuffer buffer,
            GatewayOutboxRepository repository,
            ObjectProvider<GatewayServerLifecycle> lifecycle,
            ObjectProvider<OperationsApiClient> apiClient,
            OperationsApiStatus apiStatus) {
        return registry -> {
            Gauge.builder("jt.gateway.tcp.listening", lifecycle,
                            provider -> {
                                GatewayServerLifecycle listener = provider.getIfAvailable();
                                return listener != null && listener.isListening() ? 1 : 0;
                            })
                    .register(registry);
            Gauge.builder("jt.gateway.outbox.delivering", repository,
                            target -> snapshotMetric(target, "DELIVERING"))
                    .register(registry);
            Gauge.builder("jt.gateway.outbox.dead.letter", repository,
                            target -> snapshotMetric(target, "DEAD_LETTER"))
                    .register(registry);
            Gauge.builder("jt.gateway.outbox.oldest.unresolved.age.seconds", repository,
                            target -> snapshotMetric(target, "OLDEST"))
                    .register(registry);
            Gauge.builder("jt.gateway.buffer.writable", buffer,
                            target -> target.bufferWritable() ? 1 : 0)
                    .register(registry);
            Gauge.builder("jt.gateway.outbox.pending", repository,
                            target -> {
                                try {
                                    return target.pendingCount();
                                } catch (DataAccessException unavailable) {
                                    return -1;
                                }
                            })
                    .register(registry);
            Gauge.builder("jt.gateway.operations.api.reachable", apiStatus,
                            JtGatewayRuntimeConfiguration::apiStatusMetric)
                    .register(registry);
            Gauge.builder("jt.gateway.operations.api.registry.reachable", apiStatus,
                            target -> apiSourceMetric(target, OperationsApiStatus.Source.REGISTRY))
                    .register(registry);
            Gauge.builder("jt.gateway.operations.api.ingress.reachable", apiStatus,
                            target -> apiSourceMetric(target, OperationsApiStatus.Source.INGRESS))
                    .register(registry);
            Gauge.builder("jt.gateway.operations.api.probe.reachable", apiStatus,
                            target -> apiSourceMetric(target, OperationsApiStatus.Source.PROBE))
                    .register(registry);
            Gauge.builder("jt.gateway.operations.delivery.successful", apiClient,
                            provider -> {
                                OperationsApiClient client = provider.getIfAvailable();
                                return client != null && client.deliveryAttempted()
                                        && client.lastDeliverySuccessful() ? 1 : 0;
                            })
                    .register(registry);
        };
    }

    private static double snapshotMetric(GatewayOutboxRepository repository, String field) {
        try {
            GatewayOutboxRepository.OperationalSnapshot snapshot =
                    repository.operationalSnapshot(Instant.now());
            return switch (field) {
                case "DELIVERING" -> snapshot.delivering();
                case "DEAD_LETTER" -> snapshot.deadLetter();
                case "OLDEST" -> snapshot.oldestUnresolvedAgeSeconds();
                default -> -1;
            };
        } catch (DataAccessException unavailable) {
            return -1;
        }
    }

    private static String sourceHealthState(
            boolean enabled,
            OperationsApiStatus.Snapshot snapshot,
            OperationsApiStatus.Source source) {
        if (!enabled) {
            return "DISABLED";
        }
        OperationsApiStatus.SourceSnapshot sourceSnapshot = snapshot.sources().get(source);
        if (sourceSnapshot.checkedAt() == null) {
            return "UNKNOWN";
        }
        return sourceSnapshot.fresh() ? sourceSnapshot.state().name() : "STALE";
    }

    private static double apiStatusMetric(OperationsApiStatus status) {
        return switch (status.snapshot().state()) {
            case UNKNOWN -> -1;
            case UP -> 1;
            case DOWN -> 0;
        };
    }

    private static double apiSourceMetric(
            OperationsApiStatus status, OperationsApiStatus.Source source) {
        OperationsApiStatus.SourceSnapshot snapshot = status.snapshot().sources().get(source);
        if (snapshot.checkedAt() == null) {
            return -1;
        }
        return snapshot.fresh() && snapshot.state() == OperationsApiStatus.State.UP ? 1 : 0;
    }

    private static URI endpoint(URI baseUrl, String path) {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private static ThreadPoolTaskScheduler taskScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    private static RestClient.Builder boundedBuilder(
            RestClient.Builder source, HttpTimeoutConfiguration timeouts) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeouts.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(timeouts.readTimeout());
        return source.clone().requestFactory(requestFactory);
    }

    private static int integer(Environment environment, String name, int defaultValue) {
        Integer value = environment.getProperty(name, Integer.class, defaultValue);
        if (value == null || value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    record ServiceConfiguration(
            URI baseUrl, String credential, int credentialVersion, String gatewayInstance) {
        static ServiceConfiguration from(Environment environment) {
            String rawBaseUrl = required(environment, "jt.gateway.operations-api.base-url");
            URI baseUrl;
            try {
                baseUrl = URI.create(rawBaseUrl);
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException("operations API base URL is invalid", malformed);
            }
            String scheme = baseUrl.getScheme() == null
                    ? "" : baseUrl.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || baseUrl.getHost() == null) {
                throw new IllegalArgumentException("operations API base URL must be HTTP(S)");
            }
            int version = integer(environment, "jt.gateway.service-credential.version", 0);
            if (version < 1) {
                throw new IllegalArgumentException("service credential version must be positive");
            }
            return new ServiceConfiguration(
                    baseUrl,
                    required(environment, "jt.gateway.service-credential.plaintext"),
                    version,
                    required(environment, "jt.gateway.instance"));
        }

        private static String required(Environment environment, String name) {
            String value = environment.getProperty(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must be configured when TCP is enabled");
            }
            return value;
        }
    }

    record HttpTimeoutConfiguration(Duration connectTimeout, Duration readTimeout) {
        private static final Duration DELIVERY_LEASE = Duration.ofMinutes(1);

        HttpTimeoutConfiguration {
            requireBounded(connectTimeout, "connect timeout");
            requireBounded(readTimeout, "read timeout");
        }

        static HttpTimeoutConfiguration from(Environment environment) {
            return new HttpTimeoutConfiguration(
                    Duration.ofMillis(integer(environment, "jt.gateway.http.connect-timeout-ms", 2000)),
                    Duration.ofMillis(integer(environment, "jt.gateway.http.read-timeout-ms", 5000)));
        }

        private static void requireBounded(Duration timeout, String field) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(DELIVERY_LEASE) >= 0) {
                throw new IllegalArgumentException(field + " must be positive and shorter than delivery lease");
            }
        }
    }
}
