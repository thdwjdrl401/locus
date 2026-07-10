package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.Telemetry;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 단건 직접 적재(A0): Device insert-if-absent(신규만 생성) + Telemetry persist. */
@ExtendWith(MockitoExtension.class)
class DirectIngestWriterTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private EntityManager entityManager;
    @Mock private LiveUpdatePublisher livePublisher;

    private final Instant now = Instant.parse("2026-06-20T09:00:00Z");

    private Telemetry telemetry() {
        return new Telemetry("phone-1", DeviceType.PHONE, now, now, null, Map.of());
    }

    private DirectIngestWriter writer() {
        return new DirectIngestWriter(deviceRepository, entityManager, livePublisher);
    }

    @Test
    void 새_디바이스면_생성하고_텔레메트리를_저장한다() {
        when(deviceRepository.findByDeviceId("phone-1")).thenReturn(Optional.empty());

        writer().submit(telemetry());

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(captor.capture());
        assertThat(captor.getValue().getFirstSeenAt()).isEqualTo(now);
        verify(entityManager).persist(any(Telemetry.class));
    }

    @Test
    void 기존_디바이스면_갱신하지_않고_텔레메트리만_저장한다() {
        Device existing = new Device("phone-1", DeviceType.PHONE);
        existing.setFirstSeenAt(now.minusSeconds(3600));
        when(deviceRepository.findByDeviceId("phone-1")).thenReturn(Optional.of(existing));

        writer().submit(telemetry());

        // insert-if-absent: 기존 device는 읽기만 — save 없음(라이브 상태 갱신 제거).
        verify(deviceRepository, never()).save(any(Device.class));
        assertThat(existing.getFirstSeenAt()).isEqualTo(now.minusSeconds(3600)); // 불변
        verify(entityManager).persist(any(Telemetry.class));
    }

    @Test
    void 조직_있는_디바이스는_커밋후_실시간_push한다() {
        Device withOrg = new Device("phone-1", DeviceType.PHONE);
        withOrg.setOrgId("org-3");
        when(deviceRepository.findByDeviceId("phone-1")).thenReturn(Optional.of(withOrg));

        // 트랜잭션 동기화 활성화 후 submit → 커밋(afterCommit) 시뮬레이션.
        TransactionSynchronizationManager.initSynchronization();
        try {
            writer().submit(telemetry());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(livePublisher).publish(eq("org-3"), any(TelemetryResponse.class));
    }

    @Test
    void 조직_없는_디바이스는_push하지_않는다() {
        Device noOrg = new Device("phone-1", DeviceType.PHONE); // org_id null
        when(deviceRepository.findByDeviceId("phone-1")).thenReturn(Optional.of(noOrg));

        writer().submit(telemetry());

        verifyNoInteractions(livePublisher);
    }
}
