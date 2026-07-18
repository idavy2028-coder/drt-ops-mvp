package com.idavy.drtops.domain.area;

import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VirtualStopImportService {

    private static final String HEADER = "站点名称,地址,经度,纬度,所属区域,服务半径(米),允许上车,允许下车,安全说明";
    private final VirtualStopRepository virtualStopRepository;
    private final ServiceAreaRepository serviceAreaRepository;
    private final ServiceAreaLocationChecker serviceAreaLocationChecker;
    private final AuditLogRepository auditLogRepository;

    public VirtualStopImportService(
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
    public VirtualStopImportResult importCsv(String csv, UUID actorId) {
        String normalized = csv == null ? "" : csv.replace("\uFEFF", "").replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0].trim())) {
            throw new IllegalArgumentException("导入文件表头不符合虚拟站点模板");
        }
        int created = 0;
        int skipped = 0;
        List<VirtualStopImportResult.Issue> issues = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isBlank()) {
                continue;
            }
            int rowNumber = index + 1;
            List<String> row = parseCsvRow(lines[index]);
            if (row == null || row.size() != 9) {
                skipped++;
                issues.add(new VirtualStopImportResult.Issue(rowNumber, "列数不正确"));
                continue;
            }
            String name = row.get(0).trim();
            if (name.isBlank()) {
                skipped++;
                issues.add(new VirtualStopImportResult.Issue(rowNumber, "站点名称不能为空"));
                continue;
            }
            try {
                BigDecimal longitude = new BigDecimal(row.get(2).trim());
                BigDecimal latitude = new BigDecimal(row.get(3).trim());
                if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                        || latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
                    throw new IllegalArgumentException("经纬度超出范围");
                }
                ServiceArea area = serviceAreaRepository.findFirstByName(row.get(4).trim())
                        .orElseThrow(() -> new IllegalArgumentException("所属区域不存在"));
                if (virtualStopRepository.existsByServiceAreaIdAndNameIgnoreCase(area.getId(), name)) {
                    throw new IllegalArgumentException("站点名称已存在");
                }
                int radius = Integer.parseInt(row.get(5).trim());
                boolean inside = serviceAreaLocationChecker.isInsideEnabledArea(longitude, latitude);
                VirtualStop stop = VirtualStop.createForPilot(
                        area.getId(), area.getName(), name, row.get(1).trim(),
                        "POINT(" + longitude.toPlainString() + " " + latitude.toPlainString() + ")", radius,
                        yes(row.get(6)), yes(row.get(7)), row.get(8).trim(), inside, "CSV_IMPORT", actorId);
                virtualStopRepository.save(stop);
                created++;
                if (!inside) {
                    issues.add(new VirtualStopImportResult.Issue(rowNumber, "站点位于服务区外，已按未启用状态暂存"));
                }
            } catch (IllegalArgumentException exception) {
                skipped++;
                issues.add(new VirtualStopImportResult.Issue(rowNumber, exception.getMessage()));
            }
        }
        auditLogRepository.save(AuditLog.record(
                "VIRTUAL_STOP_IMPORT", actorId, "VIRTUAL_STOP_IMPORTED", "USER", actorId.toString(), null,
                "{\"createdCount\":" + created + ",\"skippedCount\":" + skipped + "}"));
        return new VirtualStopImportResult(created, skipped, issues);
    }

    private List<String> parseCsvRow(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                columns.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        if (quoted) {
            return null;
        }
        columns.add(value.toString());
        return columns;
    }

    private boolean yes(String value) {
        return "是".equals(value.trim()) || "true".equalsIgnoreCase(value.trim());
    }
}
