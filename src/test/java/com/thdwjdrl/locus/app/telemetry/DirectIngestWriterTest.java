package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceStatus;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 단건 직접 적재(A0): Device upsert(생성/갱신) + Telemetry 저장. */
@ExtendWith(MockitoExtension.class)
class DirectIngestWriterTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private TelemetryRepository telemetryRepository;

    private final Instant now = Instant.parse("2026-06-20T09:00:00Z");

    private Telemetry telemetry() {
        return new Telemetry("phone-1", DeviceType.PHONE, now, now, null, Map.of());
    }

    private DirectIngestWriter writer() {
        return new DirectIngestWriter(deviceRepository, telemetryRepository);
    }

    @Test
    void 새_디바이스면_생성하고_텔레메트리를_저장한다() {
        when(deviceRepository.findByDeviceId("phone-1")).thenReturn(Optional.empty());

        writer().submit(telemetry());

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(captor.capture());
        assertThat(captor.getValue().getFirstSeenAt()).isEqualTo(now);
        assertThat(captor.getValue().getLastSeenAt()).isEqualTo(now);
        assertThat(captor.getValue().getStatus()).isEqualTo(DeviceStatus.ONLINE);
        verify(telemetryRepository).save(any(Telemetry.class));
    }

    @Test
    void 기존_디바이스면_갱신하고_텔레메트리를_저장한다() {
        Device existing = new Device("phone-1", DeviceType.PHONE);
        existing.setFirstSeenAt(now.minusSeconds(3600));
        when(deviceRepository.findByDeviceId("phone-1")).thenReturn(Optional.of(existing));

        writer().submit(telemetry());

        assertThat(existing.getLastSeenAt()).isEqualTo(now);
        assertThat(existing.getFirstSeenAt()).isEqualTo(now.minusSeconds(3600)); // 유지
        assertThat(existing.getStatus()).isEqualTo(DeviceStatus.ONLINE);
        verify(deviceRepository).save(existing);
        verify(telemetryRepository).save(any(Telemetry.class));
    }
}
