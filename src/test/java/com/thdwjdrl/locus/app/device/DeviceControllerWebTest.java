package com.thdwjdrl.locus.app.device;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thdwjdrl.locus.app.support.PageResponse;
import com.thdwjdrl.locus.core.domain.DeviceNotFoundException;
import com.thdwjdrl.locus.core.domain.DeviceStatus;
import com.thdwjdrl.locus.core.domain.DeviceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 디바이스 조회 웹 계층 — 서비스 mock. */
@WebMvcTest(DeviceController.class)
class DeviceControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DeviceQueryService queryService;

    private DeviceResponse sample() {
        return new DeviceResponse(
                "phone-1", DeviceType.PHONE, DeviceStatus.ONLINE, Instant.now(), Instant.now());
    }

    @Test
    void 목록은_200이고_페이징_형태로_반환() throws Exception {
        when(queryService.list(any()))
                .thenReturn(new PageResponse<>(List.of(sample()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].deviceId").value("phone-1"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void 단건_존재시_200() throws Exception {
        when(queryService.getByDeviceId("phone-1")).thenReturn(sample());

        mockMvc.perform(get("/api/devices/{id}", "phone-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("phone-1"));
    }

    @Test
    void 단건_부재시_404_DEVICE_NOT_FOUND() throws Exception {
        when(queryService.getByDeviceId(eq("missing")))
                .thenThrow(new DeviceNotFoundException("missing"));

        mockMvc.perform(get("/api/devices/{id}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
    }
}
