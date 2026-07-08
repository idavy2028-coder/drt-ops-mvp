package com.idavy.drtops.domain.order;

import com.idavy.drtops.common.ApiResponse;
import com.idavy.drtops.domain.dispatch.DispatchOrchestrator;
import com.idavy.drtops.domain.dispatch.DispatchResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public RideOrderController(RideOrderService rideOrderService, DispatchOrchestrator dispatchOrchestrator) {
        this.rideOrderService = rideOrderService;
        this.dispatchOrchestrator = dispatchOrchestrator;
    }

    @GetMapping
    ApiResponse<List<RideOrder>> list() {
        return ApiResponse.ok(rideOrderService.list());
    }

    @PostMapping
    ResponseEntity<ApiResponse<RideOrder>> create(@Valid @RequestBody RideOrderService.CreateRideOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(rideOrderService.create(request)));
    }

    @PostMapping("/{orderId}/dispatch")
    ApiResponse<DispatchResult> dispatch(@PathVariable UUID orderId) {
        return ApiResponse.ok(dispatchOrchestrator.dispatchOrder(orderId));
    }
}
