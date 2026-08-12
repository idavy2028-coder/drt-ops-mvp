package com.idavy.drtops.jtgateway.ingress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GatewayIngressBuffer {
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "authenticationtoken",
            "authenticationcode",
            "authcode",
            "uploadcredential",
            "uploadtoken",
            "attachmenturl",
            "mediaurl",
            "playbackurl");

    private final GatewayOutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicBoolean bufferWritable = new AtomicBoolean(true);

    public GatewayIngressBuffer(
            GatewayOutboxRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            repository.recoverInterruptedDeliveries(clock.instant());
        } catch (DataAccessException unavailable) {
            bufferWritable.set(false);
        }
    }

    public WriteResult append(GatewayIngressEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        validatePayload(envelope.payloadJson());
        try {
            boolean inserted = repository.insert(envelope, clock.instant());
            bufferWritable.set(true);
            return inserted ? WriteResult.STORED : WriteResult.DUPLICATE;
        } catch (DataAccessException unavailable) {
            bufferWritable.set(false);
            return WriteResult.UNAVAILABLE;
        }
    }

    public boolean bufferWritable() {
        return bufferWritable.get();
    }

    public boolean mayAcknowledgeSuccessfulPersistence() {
        return bufferWritable();
    }

    private void validatePayload(String payloadJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException malformed) {
            throw new IllegalArgumentException("payloadJson must be valid JSON", malformed);
        }
        if (root == null || containsForbiddenField(root)) {
            throw new IllegalArgumentException("payloadJson contains forbidden sensitive fields");
        }
    }

    private static boolean containsForbiddenField(JsonNode node) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (FORBIDDEN_FIELDS.contains(normalizeFieldName(name))
                        || containsForbiddenField(node.get(name))) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsForbiddenField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizeFieldName(String fieldName) {
        StringBuilder normalized = new StringBuilder(fieldName.length());
        fieldName.codePoints()
                .filter(Character::isLetterOrDigit)
                .map(Character::toLowerCase)
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    public enum WriteResult {
        STORED,
        DUPLICATE,
        UNAVAILABLE
    }
}
