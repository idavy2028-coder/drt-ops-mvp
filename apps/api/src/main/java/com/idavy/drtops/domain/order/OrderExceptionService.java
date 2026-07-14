package com.idavy.drtops.domain.order;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderExceptionService {

    private final RideOrderRepository rideOrderRepository;
    private final AuditLogRepository auditLogRepository;

    public OrderExceptionService(RideOrderRepository rideOrderRepository, AuditLogRepository auditLogRepository) {
        this.rideOrderRepository = rideOrderRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public RideOrder cancel(UUID actorId, UUID orderId, String reason) {
        RideOrder order = order(orderId);
        order.cancel(reason);
        audit(actorId, order.getId(), "ORDER_CANCELLED", reason);
        return order;
    }

    @Transactional
    public RideOrder noShow(UUID actorId, UUID orderId, String reason) {
        RideOrder order = order(orderId);
        order.closeException(reason);
        audit(actorId, order.getId(), "ORDER_NO_SHOW", reason);
        return order;
    }

    private RideOrder order(UUID orderId) {
        return rideOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
    }

    private void audit(UUID actorId, UUID orderId, String action, String reason) {
        auditLogRepository.save(AuditLog.record(
                "RIDE_ORDER",
                orderId,
                action,
                "USER",
                actorId.toString(),
                reason,
                "{}"));
    }
}
