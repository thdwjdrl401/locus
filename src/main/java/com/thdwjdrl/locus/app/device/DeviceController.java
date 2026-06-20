package com.thdwjdrl.locus.app.device;

import com.thdwjdrl.locus.app.support.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 디바이스 조회 API.
 *
 * <p>페이징은 {@code Pageable} 리졸버 대신 명시적 {@code page}/{@code size} 파라미터를 받아 슬라이스 테스트(@WebMvcTest)를
 * 단순하게 한다.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceQueryService queryService;

    public DeviceController(DeviceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public PageResponse<DeviceResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.list(PageRequest.of(page, size));
    }

    @GetMapping("/{deviceId}")
    public DeviceResponse get(@PathVariable String deviceId) {
        return queryService.getByDeviceId(deviceId);
    }
}
