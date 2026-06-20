package com.thdwjdrl.locus.app.support;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 페이징 응답의 안정적 JSON 형태.
 *
 * <p>Spring의 {@code Page}/{@code PageImpl}을 그대로 직렬화하면 형태가 불안정(경고)이라, 필요한 필드만 명시적으로 노출한다.
 */
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
