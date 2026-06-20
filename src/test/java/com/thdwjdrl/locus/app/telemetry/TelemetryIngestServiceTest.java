package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.app.device.PhoneHandler;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.BatteryDto;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.LocationDto;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.NetworkDto;
import com.thdwjdrl.locus.core.domain.ActivityType;
import com.thdwjdrl.locus.core.domain.AppState;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceStatus;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import com.thdwjdrl.locus.core.domain.NetworkType;
import com.thdwjdrl.locus.core.domain.PermissionState;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 수집 오케스트레이션: Device upsert + Telemetry 저장 + 전략 검증 게이트. */
@ExtendWith(MockitoExtension.class)
class TelemetryIngestServiceTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private TelemetryRepository telemetryRepository;

    private final Instant now = Instant.parse("2026-06-20T09:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private TelemetryIngestService service;

    @BeforeEach
    void setUp() {
        service =
                new TelemetryIngestService(
                        new TelemetryAssembler(),
                        List.of(new PhoneHandler()),
                        deviceRepository,
                        telemetryRepository,
                        clock);
    }

    private TelemetryRequest request(NetworkType networkType, boolean online) {
        return new TelemetryRequest(
                "phone-1",
                DeviceType.PHONE,
                now,
                new LocationDto(37.0, 127.0, 5.0, null, 1.0, 90.0),
                new BatteryDto(80, false),
                new NetworkDto(networkType, online),
                ActivityType.WALKING,
                AppState.FOREGROUND,
                PermissionState.WHILE_IN_USE,
                true);
    }

    @Test
    void 새_디바이스면_생성하고_텔레메트리를_저장한다() {
        when(deviceRepository.findByDeviceId("phone-1")).thenReturn(Optional.empty());

        service.ingest(request(NetworkType.CELLULAR, true));

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

        service.ingest(request(NetworkType.CELLULAR, true));

        assertThat(existing.getLastSeenAt()).isEqualTo(now);
        assertThat(existing.getFirstSeenAt()).isEqualTo(now.minusSeconds(3600)); // 유지
        verify(deviceRepository).save(existing);
        verify(telemetryRepository).save(any(Telemetry.class));
    }

    @Test
    void 전략_검증_실패면_저장하지_않는다() {
        assertThatThrownBy(() -> service.ingest(request(NetworkType.NONE, true)))
                .isInstanceOf(InvalidTelemetryException.class);

        verify(deviceRepository, never()).save(any());
        verify(telemetryRepository, never()).save(any());
    }
}
