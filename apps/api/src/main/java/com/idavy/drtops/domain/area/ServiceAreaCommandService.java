package com.idavy.drtops.domain.area;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.dispatch.DispatchRuleSetRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ServiceAreaCommandService {

    private static final String COORDINATE_SYSTEM = "GCJ02";

    private final ServiceAreaRepository serviceAreaRepository;
    private final DispatchRuleSetRepository dispatchRuleSetRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public ServiceAreaCommandService(
            ServiceAreaRepository serviceAreaRepository,
            DispatchRuleSetRepository dispatchRuleSetRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper) {
        this.serviceAreaRepository = serviceAreaRepository;
        this.dispatchRuleSetRepository = dispatchRuleSetRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ServiceAreaView saveBoundary(UUID serviceAreaId, ServiceAreaBoundaryRequest request, UUID actorId) {
        try {
            ServiceArea serviceArea = findServiceArea(serviceAreaId);
            serviceArea.replaceDraftBoundary(normalize(request), "MANUAL");
            ServiceArea saved = serviceAreaRepository.saveAndFlush(serviceArea);
            recordAudit(saved, "SERVICE_AREA_BOUNDARY_SAVED", actorId, null);
            return ServiceAreaView.from(saved);
        } catch (ObjectOptimisticLockingFailureException | jakarta.persistence.OptimisticLockException exception) {
            throw concurrentUpdateException();
        }
    }

    @Transactional
    public ServiceAreaView importDistrictDraft(String keyword, String boundaryWkt, UUID actorId) {
        String normalized = normalize(new ServiceAreaBoundaryRequest(boundaryWkt, null));
        ServiceArea serviceArea = serviceAreaRepository.findFirstByName(keyword).orElse(null);
        if (serviceArea == null) {
            serviceArea = ServiceArea.createImportedDraft(
                    UUID.randomUUID(), keyword, normalized, "06:00:00", "23:00:00", dispatchRuleSetRepository.findAll().stream()
                            .findFirst()
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先配置调度规则"))
                            .getId());
        } else {
            serviceArea.replaceDraftBoundary(normalized, "AMAP_DISTRICT");
        }
        ServiceArea saved = saveAndTranslateConflict(serviceArea);
        recordAudit(saved, "SERVICE_AREA_DISTRICT_BOUNDARY_IMPORTED", actorId, "行政区边界草稿");
        return ServiceAreaView.from(saved);
    }

    @Transactional
    public ServiceAreaView publish(UUID serviceAreaId, UUID actorId) {
        try {
            ServiceArea serviceArea = findServiceArea(serviceAreaId);
            normalize(new ServiceAreaBoundaryRequest(serviceArea.getDraftBoundary(), null));
            serviceArea.publishDraft();
            ServiceArea saved = serviceAreaRepository.saveAndFlush(serviceArea);
            recordAudit(saved, "SERVICE_AREA_PUBLISHED", actorId, null);
            return ServiceAreaView.from(saved);
        } catch (ObjectOptimisticLockingFailureException | jakarta.persistence.OptimisticLockException exception) {
            throw concurrentUpdateException();
        }
    }

    @Transactional
    public ServiceAreaView create(CreateServiceAreaCommand command, UUID actorId) {
        String boundary = normalize(new ServiceAreaBoundaryRequest(command.boundaryWkt(), null));
        ServiceArea saved = saveAndTranslateConflict(ServiceArea.create(
                command.id(), command.name(), boundary, command.serviceStart(), command.serviceEnd(), command.ruleSetId()));
        recordAudit(saved, "SERVICE_AREA_CREATED", actorId, "服务区边界草稿");
        return ServiceAreaView.from(saved);
    }

    private ServiceArea findServiceArea(UUID serviceAreaId) {
        return serviceAreaRepository.findById(serviceAreaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "服务区不存在"));
    }

    static ResponseStatusException concurrentUpdateException() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "服务区边界已被其他操作更新，请刷新后重试");
    }

    private ServiceArea saveAndTranslateConflict(ServiceArea serviceArea) {
        try {
            return serviceAreaRepository.saveAndFlush(serviceArea);
        } catch (ObjectOptimisticLockingFailureException | jakarta.persistence.OptimisticLockException exception) {
            throw concurrentUpdateException();
        }
    }

    private void recordAudit(ServiceArea serviceArea, String action, UUID actorId, String reason) {
        auditLogRepository.save(AuditLog.record(
                "SERVICE_AREA",
                serviceArea.getId(),
                action,
                "USER",
                actorId.toString(),
                reason,
                "{\"boundaryVersion\":" + serviceArea.getBoundaryVersion() + ",\"coordinateSystem\":\""
                        + COORDINATE_SYSTEM + "\"}"));
    }

    private String normalize(ServiceAreaBoundaryRequest request) {
        if (request == null) {
            throw invalid("服务区边界不能为空");
        }
        String wkt = hasText(request.boundaryWkt()) ? request.boundaryWkt().trim() : geoJsonToWkt(request.geoJson());
        if (!hasText(wkt)) {
            throw invalid("服务区边界不能为空");
        }
        String upperCaseWkt = wkt.toUpperCase(Locale.ROOT);
        int ringStart = upperCaseWkt.indexOf("((");
        if (!upperCaseWkt.startsWith("POLYGON") || ringStart < 0 || !wkt.endsWith("))")) {
            throw invalid("服务区边界必须是Polygon");
        }
        String body = wkt.substring(ringStart + 2, wkt.length() - 2);
        List<Point> points = new ArrayList<>();
        for (String value : body.split(",")) {
            String[] parts = value.trim().split("\\s+");
            if (parts.length != 2) {
                throw invalid("服务区边界坐标格式不合法");
            }
            try {
                Point point = new Point(new BigDecimal(parts[0]), new BigDecimal(parts[1]));
                if (point.longitude().compareTo(BigDecimal.valueOf(-180)) < 0
                        || point.longitude().compareTo(BigDecimal.valueOf(180)) > 0
                        || point.latitude().compareTo(BigDecimal.valueOf(-90)) < 0
                        || point.latitude().compareTo(BigDecimal.valueOf(90)) > 0) {
                    throw invalid("服务区边界坐标范围不合法");
                }
                points.add(point);
            } catch (NumberFormatException exception) {
                throw invalid("服务区边界坐标格式不合法");
            }
        }
        if (points.size() < 4 || points.stream().limit(points.size() - 1).distinct().count() < 3) {
            throw invalid("服务区边界至少需要三个点");
        }
        if (points.getFirst().longitude().compareTo(points.getLast().longitude()) != 0
                || points.getFirst().latitude().compareTo(points.getLast().latitude()) != 0) {
            throw invalid("服务区边界必须闭合");
        }
        return "POLYGON((" + points.stream()
                .map(point -> point.longitude().toPlainString() + " " + point.latitude().toPlainString())
                .reduce((left, right) -> left + "," + right)
                .orElseThrow() + "))";
    }

    private String geoJsonToWkt(String geoJson) {
        if (!hasText(geoJson)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(geoJson);
            if (!"Polygon".equals(root.path("type").asText())) {
                throw invalid("服务区边界必须是Polygon");
            }
            JsonNode ring = root.path("coordinates").path(0);
            List<String> coordinates = new ArrayList<>();
            for (JsonNode coordinate : ring) {
                if (coordinate.size() != 2) {
                    throw invalid("服务区边界坐标格式不合法");
                }
                coordinates.add(coordinate.get(0).asText() + " " + coordinate.get(1).asText());
            }
            return "POLYGON((" + String.join(",", coordinates) + "))";
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("服务区边界GeoJSON格式不合法");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Point(BigDecimal longitude, BigDecimal latitude) {
    }

    public record CreateServiceAreaCommand(
            UUID id,
            String name,
            String boundaryWkt,
            String serviceStart,
            String serviceEnd,
            UUID ruleSetId) {
    }
}
