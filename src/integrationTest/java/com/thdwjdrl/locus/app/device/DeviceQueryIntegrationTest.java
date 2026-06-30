package com.thdwjdrl.locus.app.device;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thdwjdrl.locus.IntegrationTestBase;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** 디바이스 조회 e2e — 실 TimescaleDB/PostgreSQL. */
class DeviceQueryIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private DeviceRepository deviceRepository;

    @BeforeEach
    void clean() {
        deviceRepository.deleteAll();
        deviceRepository.save(new Device("phone-1", DeviceType.PHONE));
    }

    @Test
    void 목록은_저장된_디바이스를_페이징으로_반환() throws Exception {
        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].deviceId").value("phone-1"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void 단건_존재시_200_부재시_404() throws Exception {
        mockMvc.perform(get("/api/devices/{id}", "phone-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("phone-1"));

        mockMvc.perform(get("/api/devices/{id}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
    }
}
