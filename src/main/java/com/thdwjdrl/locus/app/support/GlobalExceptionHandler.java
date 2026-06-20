package com.thdwjdrl.locus.app.support;

import com.thdwjdrl.locus.core.domain.DeviceNotFoundException;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 → 표준 에러 응답.
 *
 * <p>Bean Validation 실패는 필드별 메시지로 400, 본문 파싱 실패(잘못된 JSON·알 수 없는 enum 값)도 400.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(MethodArgumentNotValidException ex) {
        var fieldErrors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                        .toList();
        return new ApiError("VALIDATION_FAILED", "요청 검증에 실패했습니다", fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleUnreadable(HttpMessageNotReadableException ex) {
        return ApiError.of("MALFORMED_REQUEST", "요청 본문을 읽을 수 없습니다");
    }

    @ExceptionHandler(InvalidTelemetryException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleInvalidTelemetry(InvalidTelemetryException ex) {
        return ApiError.of("INVALID_TELEMETRY", ex.getMessage());
    }

    @ExceptionHandler(DeviceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleDeviceNotFound(DeviceNotFoundException ex) {
        return ApiError.of("DEVICE_NOT_FOUND", ex.getMessage());
    }

    // 멱등: UNIQUE(device_id, recorded_at) 위반 → 중복 텔레메트리. M0는 이 정도로(본격 멱등은 M2).
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleDuplicate(DataIntegrityViolationException ex) {
        return ApiError.of("DUPLICATE", "이미 수신된 텔레메트리입니다 (deviceId+timestamp 중복)");
    }
}
