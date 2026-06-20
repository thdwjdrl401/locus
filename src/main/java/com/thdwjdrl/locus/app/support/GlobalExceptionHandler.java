package com.thdwjdrl.locus.app.support;

import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
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
}
