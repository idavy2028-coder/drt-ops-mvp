package com.idavy.drtops.common;

import com.idavy.drtops.domain.map.MapProviderException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MapProviderException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleMapProvider(MapProviderException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.ok(Map.of("message", exception.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(this::formatFieldError)
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(ApiResponse.ok(Map.of("message", message)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(this::formatConstraintViolation)
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(ApiResponse.ok(Map.of("message", message)));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleMissingRequestParameter(
            MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.ok(Map.of(
                "message", "缺少请求参数：" + exception.getParameterName())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.ok(Map.of("message", "请求体格式不合法")));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return ResponseEntity.status(status)
                .body(ApiResponse.ok(Map.of("message", exception.getReason() == null ? status.getReasonPhrase() : exception.getReason())));
    }

    private String formatFieldError(FieldError fieldError) {
        return validationMessage(fieldError.getField(), fieldError.getCode());
    }

    private String formatConstraintViolation(ConstraintViolation<?> violation) {
        String propertyPath = violation.getPropertyPath().toString();
        String field = propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
        String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return validationMessage(field, constraint);
    }

    private String validationMessage(String field, String constraint) {
        if ("NotBlank".equals(constraint) || "NotNull".equals(constraint)) {
            return field + "不能为空";
        }
        if ("DecimalMin".equals(constraint) || "DecimalMax".equals(constraint)) {
            return field + "坐标范围不合法";
        }
        return "请求参数不合法";
    }
}
