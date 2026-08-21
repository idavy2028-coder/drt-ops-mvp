package com.idavy.drtops.domain.location;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.common.ApiResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/jt-gateway")
public class GpsLocationIngressController {
    private final GatewayIngressRouter router;
    private final ObjectMapper objectMapper;
    public GpsLocationIngressController(GatewayIngressRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }
    @PostMapping("/ingress")
    public ApiResponse<List<GpsLocationIngressService.Result>> ingress(@RequestBody JsonNode rawBatch) {
        if (rawBatch == null || !rawBatch.isArray() || rawBatch.isEmpty() || rawBatch.size() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ingress batch");
        }
        List<UUID> keys = new ArrayList<>(rawBatch.size());
        Set<UUID> uniqueKeys = new HashSet<>();
        for (JsonNode raw : rawBatch) {
            UUID key = correlatableKey(raw);
            if (!uniqueKeys.add(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ingress batch keys");
            }
            keys.add(key);
        }
        List<GatewayIngressEnvelope> batch = new ArrayList<>(rawBatch.size());
        for (int index = 0; index < rawBatch.size(); index++) {
            batch.add(toEnvelope(rawBatch.get(index), keys.get(index)));
        }
        return ApiResponse.ok(router.ingest(List.copyOf(batch)));
    }

    private static UUID correlatableKey(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ingress batch keys");
        }
        JsonNode keyNode = raw.get("idempotencyKey");
        if (keyNode == null || !keyNode.isTextual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ingress batch keys");
        }
        try {
            UUID key = UUID.fromString(keyNode.textValue());
            if (!key.toString().equalsIgnoreCase(keyNode.textValue())) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return key;
        } catch (IllegalArgumentException malformed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ingress batch keys");
        }
    }

    private GatewayIngressEnvelope toEnvelope(JsonNode raw, UUID key) {
        JsonNode schemaNode = raw.get("schemaVersion");
        int schemaVersion = schemaNode != null && schemaNode.isIntegralNumber() && schemaNode.canConvertToInt()
                ? schemaNode.intValue() : Integer.MIN_VALUE;
        JsonNode kindNode = raw.get("kind");
        String kind = kindNode != null && kindNode.isTextual() ? kindNode.textValue() : null;
        Instant receivedAt = parseInstant(raw.get("gatewayReceivedAt"));
        JsonNode payloadNode = raw.get("payloadJson");
        String payloadJson = payloadNode != null && payloadNode.isTextual()
                ? payloadNode.textValue() : null;
        return new GatewayIngressEnvelope(schemaVersion, key, kind, receivedAt, payloadJson);
    }

    private Instant parseInstant(JsonNode value) {
        if (value == null || !(value.isTextual() || value.isNumber())) {
            return null;
        }
        try {
            return objectMapper.treeToValue(value, Instant.class);
        } catch (JsonProcessingException malformed) {
            return null;
        }
    }
}
