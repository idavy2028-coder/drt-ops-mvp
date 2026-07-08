package com.idavy.drtops.domain.audit;

import com.idavy.drtops.common.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    ApiResponse<List<AuditLog>> list(@RequestParam(required = false) UUID entityId) {
        return ApiResponse.ok(auditLogService.list(entityId));
    }
}
