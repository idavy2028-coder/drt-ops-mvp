package com.idavy.drtops.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.integration.algorithm.AlgorithmUnavailableException;
import java.net.ConnectException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void returnsChineseBusinessMessageForIllegalArgument() {
        var response = handler.handleIllegalArgument(new IllegalArgumentException("导入文件表头不符合虚拟站点模板"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(ApiResponse.ok(Map.of("message", "导入文件表头不符合虚拟站点模板")));
    }

    @Test
    void returnsStableCodeForAlgorithmUnavailableWithoutLeakingCause() {
        var response = handler.handleAlgorithmUnavailable(
                new AlgorithmUnavailableException(new ConnectException("127.0.0.1:8090 refused")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(ApiResponse.ok(Map.of(
                "code", "ALGORITHM_UNAVAILABLE",
                "message", "算法服务不可用")));
        assertThat(response.getBody().data().toString()).doesNotContain("127.0.0.1", "refused");
    }
}
