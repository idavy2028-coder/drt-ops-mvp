package com.idavy.drtops.domain.order;

import com.idavy.drtops.common.ApiResponse;
import com.idavy.drtops.domain.dispatch.DispatchOrchestrator;
import com.idavy.drtops.domain.dispatch.DispatchResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class RideOrderController {

    private final RideOrderService rideOrderService;
    private final DispatchOrchestrator dispatchOrchestrator;
    private final OrderExceptionService orderExceptionService;

    public RideOrderController(
            RideOrderService rideOrderService,
            DispatchOrchestrator dispatchOrchestrator,
            OrderExceptionService orderExceptionService) {
        this.rideOrderService = rideOrderService;
        this.dispatchOrchestrator = dispatchOrchestrator;
        this.orderExceptionService = orderExceptionService;
    }

    @GetMapping
    ApiResponse<List<RideOrder>> list() {
        return ApiResponse.ok(rideOrderService.list());
    }

    @PostMapping
    ResponseEntity<ApiResponse<RideOrder>> create(
            Authentication authentication, @Valid @RequestBody RideOrderService.CreateRideOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(rideOrderService.create(actorId(authentication), request)));
    }

    @PostMapping("/{orderId}/dispatch")
    ApiResponse<DispatchResult> dispatch(@PathVariable UUID orderId) {
        return ApiResponse.ok(dispatchOrchestrator.dispatchOrder(orderId));
    }

    @PostMapping("/{orderId}/cancel")
    ApiResponse<RideOrder> cancel(
            Authentication authentication, @PathVariable UUID orderId, @RequestBody ReasonRequest request) {
        return ApiResponse.ok(orderExceptionService.cancel(actorId(authentication), orderId, request.reason()));
    }

    @PostMapping("/{orderId}/no-show")
    ApiResponse<RideOrder> noShow(
            Authentication authentication, @PathVariable UUID orderId, @RequestBody ReasonRequest request) {
        return ApiResponse.ok(orderExceptionService.noShow(actorId(authentication), orderId, request.reason()));
    }

    private UUID actorId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof UUID actorId) {
            return actorId;
        }
        return UUID.fromString("00000000-0000-0000-0000-000000000002");
    }

    public record ReasonRequest(String reason) {
    }
}
