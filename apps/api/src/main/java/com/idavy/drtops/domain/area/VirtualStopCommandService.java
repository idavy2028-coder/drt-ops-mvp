package com.idavy.drtops.domain.area;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VirtualStopCommandService {

    private final VirtualStopRepository virtualStopRepository;
    private final ServiceAreaRepository serviceAreaRepository;
    private final ServiceAreaLocationChecker serviceAreaLocationChecker;
    private final AuditLogRepository auditLogRepository;

    public VirtualStopCommandService(
            VirtualStopRepository virtualStopRepository,
            ServiceAreaRepository serviceAreaRepository,
            ServiceAreaLocationChecker serviceAreaLocationChecker,
            AuditLogRepository auditLogRepository) {
        this.virtualStopRepository = virtualStopRepository;
        this.serviceAreaRepository = serviceAreaRepository;
        this.serviceAreaLocationChecker = serviceAreaLocationChecker;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public VirtualStopView create(Command command, UUID actorId) {
        validate(command);
        ServiceArea area = area(command.serviceAreaId());
        if (virtualStopRepository.existsByServiceAreaIdAndNameIgnoreCase(area.getId(), command.name())) {
            throw invalid("同一服务区内站点名称已存在");
        }
        boolean inside = serviceAreaLocationChecker.isInsideEnabledArea(command.longitude(), command.latitude());
        VirtualStop stop = VirtualStop.createForPilot(
                area.getId(), area.getName(), command.name().trim(), trim(command.address()), wkt(command), command.serviceRadiusMeters(),
                command.boardingEnabled(), command.alightingEnabled(), trim(command.safetyNote()), inside && command.enabled(), "MANUAL", actorId);
        return record(virtualStopRepository.save(stop), actorId, "VIRTUAL_STOP_CREATED");
    }

    @Transactional
    public VirtualStopView update(UUID stopId, Command command, UUID actorId) {
        VirtualStop stop = virtualStopRepository.findById(stopId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "虚拟站点不存在"));
        validate(command);
        if (!stop.getServiceAreaId().equals(command.serviceAreaId())) {
            throw invalid("虚拟站点不支持跨服务区编辑");
        }
        boolean duplicate = virtualStopRepository.existsByServiceAreaIdAndNameIgnoreCase(stop.getServiceAreaId(), command.name())
                && !stop.getName().equalsIgnoreCase(command.name().trim());
        if (duplicate) {
            throw invalid("同一服务区内站点名称已存在");
        }
        boolean inside = serviceAreaLocationChecker.isInsideEnabledArea(command.longitude(), command.latitude());
        stop.updateForPilot(command.name().trim(), trim(command.address()), wkt(command), command.serviceRadiusMeters(),
                command.boardingEnabled(), command.alightingEnabled(), trim(command.safetyNote()), inside && command.enabled(), actorId);
        return record(virtualStopRepository.save(stop), actorId, "VIRTUAL_STOP_UPDATED");
    }

    private VirtualStopView record(VirtualStop stop, UUID actorId, String action) {
        auditLogRepository.save(AuditLog.record("VIRTUAL_STOP", stop.getId(), action, "USER", actorId.toString(), null,
                "{\"serviceAreaId\":\"" + stop.getServiceAreaId() + "\",\"enabled\":" + stop.isEnabled() + "}"));
        return VirtualStopView.from(stop);
    }

    private ServiceArea area(UUID areaId) {
        return serviceAreaRepository.findById(areaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "所属服务区不存在"));
    }

    private void validate(Command command) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            throw invalid("站点名称不能为空");
        }
        if (command.serviceRadiusMeters() == null || command.serviceRadiusMeters() <= 0) {
            throw invalid("服务半径必须为正数");
        }
        if (command.longitude() == null || command.longitude().compareTo(BigDecimal.valueOf(-180)) < 0
                || command.longitude().compareTo(BigDecimal.valueOf(180)) > 0
                || command.latitude() == null || command.latitude().compareTo(BigDecimal.valueOf(-90)) < 0
                || command.latitude().compareTo(BigDecimal.valueOf(90)) > 0) {
            throw invalid("经纬度范围不合法");
        }
    }

    private String wkt(Command command) {
        return "POINT(" + command.longitude().toPlainString() + " " + command.latitude().toPlainString() + ")";
    }

    private String trim(String value) { return value == null ? "" : value.trim(); }

    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    public record Command(
            UUID serviceAreaId,
            String name,
            String address,
            BigDecimal longitude,
            BigDecimal latitude,
            Integer serviceRadiusMeters,
            boolean boardingEnabled,
            boolean alightingEnabled,
            String safetyNote,
            boolean enabled) {
    }
}
