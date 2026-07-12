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

    private static final String SYSTEM_ACTOR_TYPE = "SYSTEM";
    private static final String SYSTEM_ACTOR_ID = "order-exception";

    private final RideOrderRepository rideOrderRepository;
    private final AuditLogRepository auditLogRepository;

    public OrderExceptionService(RideOrderRepository rideOrderRepository, AuditLogRepository auditLogRepository) {
        this.rideOrderRepository = rideOrderRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public RideOrder cancel(UUID orderId, String reason) {
        RideOrder order = order(orderId);
        order.cancel(reason);
        audit(order.getId(), "ORDER_CANCELLED", reason);
        return order;
    }

    @Transactional
    public RideOrder noShow(UUID orderId, String reason) {
        RideOrder order = order(orderId);
        order.closeException(reason);
        audit(order.getId(), "ORDER_NO_SHOW", reason);
        return order;
    }

    private RideOrder order(UUID orderId) {
        return rideOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
    }

    private void audit(UUID orderId, String action, String reason) {
        auditLogRepository.save(AuditLog.record(
                "RIDE_ORDER",
                orderId,
                action,
                SYSTEM_ACTOR_TYPE,
                SYSTEM_ACTOR_ID,
                reason,
                "{}"));
    }
}
