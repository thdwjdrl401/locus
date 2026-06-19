package com.thdwjdrl.locus.app.support;

import java.util.List;

/** 표준 에러 응답. */
public record ApiError(String code, String message, List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {}

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, List.of());
    }
}
