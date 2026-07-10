package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.Telemetry;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 단건 직접 적재 (M1 A0 — 기본 모드).
 *
 * <p>요청 1건 = 트랜잭션 1개 = 커밋 1회 = fsync ≥1회. M0에서 잰 ~33 req/s 최대 처리량의 경로다. {@code locus.ingest.mode}가
 * 없거나 {@code direct}면 이 구현이 선택된다(기존 동작 보존).
 *
 * <p>큐·배치는 {@link QueuedIngestWriter}(M1 A2). 이 둘이 ADR 0004의 before/after 구현.
 *
 * <p>telemetry 저장은 {@code entityManager.persist()}로 직접 INSERT. 복합 PK(device_id, recorded_at)는 항상
 * non-null이라 Spring Data JPA {@code save()}가 {@code merge()}를 택해 불필요한 SELECT를 유발하므로, persist로 강제해
 * INSERT 의미론과 중복 시 {@code DataIntegrityViolationException}(→409)을 보존한다.
 */
@Component
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "direct", matchIfMissing = true)
public class DirectIngestWriter implements TelemetryIngestPort {

    private final DeviceRepository deviceRepository;
    private final EntityManager entityManager;
    private final LiveUpdatePublisher livePublisher;

    public DirectIngestWriter(
            DeviceRepository deviceRepository,
            EntityManager entityManager,
            LiveUpdatePublisher livePublisher) {
        this.deviceRepository = deviceRepository;
        this.entityManager = entityManager;
        this.livePublisher = livePublisher;
    }

    @Override
    @Transactional
    public void submit(Telemetry telemetry) {
        Device device = ensureRegistered(telemetry);
        entityManager.persist(telemetry);
        publishAfterCommit(device.getOrgId(), telemetry);
    }

    /**
     * 커밋 후 실시간 구독자에게 push (롤백 시 안 보냄 — 안 들어간 위치를 지도에 안 그림). org 없는 미enroll 디바이스는 라우팅 대상이 없어 건너뛴다.
     * B(M4b Streams)에선 이 자리를 monitoring 컨슈머가 대체한다(포트 동일).
     */
    private void publishAfterCommit(String orgId, Telemetry telemetry) {
        if (orgId == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        livePublisher.publish(orgId, TelemetryResponse.from(telemetry));
                    }
                });
    }

    /**
     * 수집 경로에서 device를 insert-if-absent(레지스트리) — 처음 본 device만 INSERT하고, 라이브 상태(last_seen·status)는
     * 갱신하지 않는다(그건 M4 최신상태 프로젝션이 소유). 기존 device는 managed 엔티티를 읽기만 하고 mutate하지 않으므로 flush 시 UPDATE가
     * 나가지 않는다. FK 없이도 고아 telemetry를 막고, publish용 orgId를 확보한다.
     */
    private Device ensureRegistered(Telemetry telemetry) {
        return deviceRepository
                .findByDeviceId(telemetry.getDeviceId())
                .orElseGet(
                        () -> {
                            Device created =
                                    new Device(telemetry.getDeviceId(), telemetry.getDeviceType());
                            created.setFirstSeenAt(telemetry.getReceivedAt());
                            deviceRepository.save(created);
                            return created;
                        });
    }
}
