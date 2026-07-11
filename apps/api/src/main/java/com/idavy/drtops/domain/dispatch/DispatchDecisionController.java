package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dispatch-decisions")
public class DispatchDecisionController {

    private final DispatchDecisionReadService dispatchDecisionReadService;

    public DispatchDecisionController(DispatchDecisionReadService dispatchDecisionReadService) {
        this.dispatchDecisionReadService = dispatchDecisionReadService;
    }

    @GetMapping
    ApiResponse<List<DispatchDecisionReadService.DecisionSummary>> list() {
        return ApiResponse.ok(dispatchDecisionReadService.list());
    }
}
