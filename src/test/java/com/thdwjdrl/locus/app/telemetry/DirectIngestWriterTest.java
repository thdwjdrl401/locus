package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceStatus;
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

/** 단건 직접 적재(A0): Device upsert(생성/갱신) + Telemetry persist. */
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
        return writer(null);
    }

    private DirectIngestWriter writer(String defaultOrg) {
        IngestProperties props = new IngestProperties();
        props.setDefaultOrg(defaultOrg);
        return new DirectIngestWriter(deviceRepository, entityManager, livePublisher, props);
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
        verify(entityManager).persist(any(Telemetry.class));
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
        verify(entityManager).persist(any(Telemetry.class));
    }

    /** org가 있으면 커밋 후 push를 등록하므로 트랜잭션 동기화가 필요하다. */
    private void submitWithSynchronization(DirectIngestWriter writer) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            writer.submit(telemetry());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 기본_org가_설정되면_새_디바이스를_그_조직으로_만든다() {
        when(deviceRepository.findByDeviceId("phone-1")).thenReturn(Optional.empty());

        submitWithSynchronization(writer("org-0"));

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(captor.capture());
        assertThat(captor.getValue().getOrgId()).isEqualTo("org-0");
    }

    @Test
    void 기본_org가_없으면_새_디바이스는_org없이_만든다() {
        when(deviceRepository.findByDeviceId("phone-1")).thenReturn(Optional.empty());

        writer().submit(telemetry());

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(captor.capture());
        assertThat(captor.getValue().getOrgId()).isNull();
    }

    @Test
    void 기본_org는_기존_디바이스의_조직을_덮어쓰지_않는다() {
        Device existing = new Device("phone-1", DeviceType.PHONE);
        existing.setOrgId("org-9");
        when(deviceRepository.findByDeviceId("phone-1")).thenReturn(Optional.of(existing));

        submitWithSynchronization(writer("org-0"));

        assertThat(existing.getOrgId()).isEqualTo("org-9");
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
