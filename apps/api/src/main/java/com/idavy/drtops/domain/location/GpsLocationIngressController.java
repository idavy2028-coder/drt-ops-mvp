package com.idavy.drtops.domain.location;

import com.idavy.drtops.common.ApiResponse;
import java.util.List;
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
    public GpsLocationIngressController(GatewayIngressRouter router) { this.router = router; }
    @PostMapping("/ingress")
    public ApiResponse<List<GpsLocationIngressService.Result>> ingress(@RequestBody List<GatewayIngressEnvelope> batch) {
        if (batch == null || batch.isEmpty() || batch.size() > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ingress batch");
        return ApiResponse.ok(router.ingest(batch));
    }
}
