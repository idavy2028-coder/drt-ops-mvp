package com.idavy.drtops.common;

import static org.assertj.core.api.Assertions.assertThat;

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
}
