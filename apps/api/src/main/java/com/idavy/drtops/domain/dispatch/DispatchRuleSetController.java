package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/dispatch-rule-sets")
public class DispatchRuleSetController {

    private final DispatchRuleSetRepository repository;

    public DispatchRuleSetController(DispatchRuleSetRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    ApiResponse<List<DispatchRuleSet>> list() {
        return ApiResponse.ok(repository.findAll());
    }

    @PutMapping("/{id}")
    ResponseEntity<ApiResponse<DispatchRuleSet>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDispatchRuleSetRequest request) {
        DispatchRuleSet ruleSet = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则组不存在"));
        ruleSet.updateRules(
                request.maxWaitMinutes(),
                request.maxDetourMinutes(),
                request.bookingWindowMinutes(),
                request.autoDispatchScoreThreshold(),
                request.manualReviewScoreThreshold(),
                request.waitWeight(),
                request.detourWeight(),
                request.stabilityWeight(),
                request.utilizationWeight(),
                request.insertionPolicy());
        return ResponseEntity.ok(ApiResponse.ok(repository.save(ruleSet)));
    }

    public record UpdateDispatchRuleSetRequest(
            @NotNull @Positive Integer maxWaitMinutes,
            @NotNull @PositiveOrZero Integer maxDetourMinutes,
            @NotNull @Positive Integer bookingWindowMinutes,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal autoDispatchScoreThreshold,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal manualReviewScoreThreshold,
            @NotNull @DecimalMin("0.00") BigDecimal waitWeight,
            @NotNull @DecimalMin("0.00") BigDecimal detourWeight,
            @NotNull @DecimalMin("0.00") BigDecimal stabilityWeight,
            @NotNull @DecimalMin("0.00") BigDecimal utilizationWeight,
            @NotBlank String insertionPolicy) {
    }
}
